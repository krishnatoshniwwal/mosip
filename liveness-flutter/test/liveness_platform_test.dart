import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mosip_face_liveness/mosip_face_liveness.dart';

/// The channel contract with the Java side (MosipFaceLivenessPlugin and FlutterLivenessBridge).
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final platform = MethodChannelLivenessPlatform();
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(platform.methods,
        (call) async {
      calls.add(call);
      return switch (call.method) {
        'start' => {
            'sessionId': 'abc',
            'status': 'PASSIVE_CHECK',
            'captureArea': {'enabled': true, 'centerX': 0.5, 'centerY': 0.48, 'radiusX': 0.2, 'radiusY': 0.3},
            'rtl': false,
          },
        'labels' => {'retry': 'Réessayer', 'rtl': false, 'language': 'fr', 'lockedUntil': "Bloqué jusqu''à {0}"},
        'requestCameraPermission' => true,
        'initialize' => {'mode': 'simulated', 'engine': 'sim'},
        'metrics' => {'avgFrameProcessingMs': 41.5, 'framesProcessed': 120, 'failureRate': 0.0},
        'syncModels' => {'status': 'UP_TO_DATE', 'detail': 'installed sequence 3', 'offered': 'models 1.3.0 (seq 3)'},
        _ => null,
      };
    });
  });

  tearDown(() =>
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(platform.methods, null));

  test('start sends the names the bridge reads and parses the session', () async {
    final start = await platform.start(
      workflow: LivenessWorkflow.supervisorAuthentication,
      subjectKey: '110012',
      consentGranted: true,
      language: 'ar',
      consentNoticeVersion: 'camera-notice-v1',
    );

    expect(calls.single.method, 'start');
    expect(calls.single.arguments, {
      'workflow': 'SUPERVISOR_AUTHENTICATION',
      'subjectKey': '110012',
      'consentGranted': true,
      'language': 'ar',
      'consentNoticeVersion': 'camera-notice-v1',
    });
    expect(start.sessionId, 'abc');
    expect(start.captureArea.radiusX, 0.2);
  });

  test('initialize sends the mode, synced settings and trusted keys', () async {
    await platform.initialize(simulated: true, config: {'mosip.registration.liveness.passive.threshold': '75'});

    expect(calls.single.arguments, {
      'mode': 'simulated',
      'config': {'mosip.registration.liveness.passive.threshold': '75'},
      'trustedKeys': null,
      'lensFacing': 'FRONT',
    });
  });

  test('a model update server is passed only when set', () async {
    await platform.initialize(
        trustedKeys: ['-----BEGIN PUBLIC KEY-----'],
        modelUpdateUrl: 'https://updates.example.org/liveness/',
        modelSyncIntervalMinutes: 60);

    expect(calls.single.arguments, {
      'mode': 'camera',
      'config': null,
      'trustedKeys': ['-----BEGIN PUBLIC KEY-----'],
      'lensFacing': 'FRONT',
      'modelUpdateUrl': 'https://updates.example.org/liveness/',
      'modelSyncIntervalMinutes': 60,
    });
  });

  test('metrics and model sync', () async {
    final metrics = await platform.metrics();
    final sync = await platform.syncModels();

    expect(metrics!['avgFrameProcessingMs'], 41.5);
    expect(sync['status'], 'UP_TO_DATE');
    expect(calls.map((c) => c.method), ['metrics', 'syncModels']);

    final event = LivenessEvent.fromMap({'eventType': 'modelSync', 'status': 'UPDATED', 'offered': 'models 1.4.0 (seq 4)'})
        as ModelSyncEvent;
    expect(event.status, 'UPDATED');
    expect(event.offered, 'models 1.4.0 (seq 4)');
  });

  test('labels are parsed', () async {
    final labels = await platform.labels('fra');

    expect(calls.single.arguments, {'language': 'fra'});
    expect(labels.retry, 'Réessayer');
    expect(labels.language, 'fr');
    expect(labels.lockedUntil('10:30'), "Bloqué jusqu'à 10:30");
  });

  test('events are parsed as the Java bridge serialises them', () {
    final u = LivenessEvent.fromMap({
      'eventType': 'update', 'sessionId': 's', 'workflow': 'RESIDENT_REGISTRATION', 'state': 'ACTIVE_CHALLENGE',
      'message': 'Please blink', 'messageCode': 'CHALLENGE_BLINK', 'messageText': 'Veuillez cligner des yeux',
      'challenge': 'BLINK', 'challengeNumber': 1, 'challengeCount': 2, 'remainingMs': 4000, 'progress': 0.5,
      'attempt': 1, 'maxAttempts': 3, 'retryAvailable': false, 'tips': <Object?>[], 'tipsText': <Object?>[],
      'stepText': 'Défi 1 sur 2', 'timeLeftText': 'Temps restant : 4 s', 'attemptText': 'Tentative 1 sur 3',
    }) as LivenessUpdate;
    expect(u.messageText, 'Veuillez cligner des yeux');
    expect(u.challenge, 'BLINK');
    expect(u.progress, 0.5);

    final english = LivenessEvent.fromMap({
      'eventType': 'update', 'sessionId': 's', 'state': 'PASSIVE_CHECK', 'message': 'Checking face liveness…',
      'tips': ['Improve lighting'],
    }) as LivenessUpdate;
    expect(english.messageText, 'Checking face liveness…', reason: 'older bridges send only the English text');
    expect(english.tips, ['Improve lighting']);

    final r = LivenessEvent.fromMap({
      'eventType': 'complete', 'sessionId': 's', 'workflow': 'RESIDENT_REGISTRATION', 'outcome': 'PASSED',
      'accepted': true, 'passiveScore': null, 'activeVerificationPerformed': true,
      'challengesCompleted': ['BLINK', 'TURN_LEFT'], 'attempts': 2, 'durationMs': 5400,
      'capturedJpeg': Uint8List.fromList([1, 2, 3]),
    }) as LivenessResult;
    expect(r.accepted, isTrue);
    expect(r.challengesCompleted, ['BLINK', 'TURN_LEFT']);
    expect(r.passiveScore, isNull);
    expect(r.capturedJpeg, [1, 2, 3]);

    final audit = LivenessEvent.fromMap({
      'eventType': 'audit', 'code': 'REG-LIV-009', 'type': 'LIVENESS_PASSED', 'attempt': 1,
      'details': {'passiveScore': '83.1'},
    }) as LivenessAuditEvent;
    expect(audit.details['passiveScore'], '83.1');

    expect(LivenessEvent.fromMap({'eventType': 'something-new'}), isNull);
  });
}
