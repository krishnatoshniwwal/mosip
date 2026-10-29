import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mosip_face_liveness/mosip_face_liveness.dart';

import 'fake_liveness_platform.dart';

void main() {
  late FakeLivenessPlatform platform;
  LivenessResult? closedWith;

  /// Opens the screen from a home page, the way a client screen does, and records what it closes with.
  Future<void> open(WidgetTester tester,
      {LivenessWorkflow workflow = LivenessWorkflow.operatorAuthentication, String language = 'en'}) async {
    tester.view.physicalSize = const Size(1080, 2160);
    tester.view.devicePixelRatio = 2.5;
    addTearDown(tester.view.reset);
    closedWith = null;
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () async => closedWith = await LivenessCaptureScreen.open(context,
              platform: platform,
              workflow: workflow,
              title: 'Login with face',
              subjectKey: '110011',
              language: language,
              online: false),
          child: const Text('open'),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  setUp(() => platform = FakeLivenessPlatform());

  /// Answers the camera notice. Not pumpAndSettle: until the first update the progress bar is indeterminate and
  /// animates for as long as the session runs.
  Future<void> allow(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('liveness-allow')));
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  }

  testWidgets('asks before the camera is turned on', (tester) async {
    await open(tester);

    expect(find.text('Camera permission'), findsOneWidget);
    expect(find.byKey(const Key('camera-preview')), findsNothing);
    expect(platform.calls, ['labels:en'], reason: 'no permission request and no session before the answer');
  });

  testWidgets('a refusal is passed to the session without asking Android for the camera', (tester) async {
    await open(tester);

    await tester.tap(find.byKey(const Key('liveness-deny')));
    await tester.pump();
    platform.emit({
      'eventType': 'complete', 'sessionId': 's1', 'outcome': 'FAILED', 'accepted': false,
      'failureReason': 'CONSENT_DECLINED', 'requiredAction': 'BLOCK',
    });
    await tester.pump();

    expect(platform.calls, isNot(contains('requestCameraPermission')));
    expect(platform.startArgs!['consentGranted'], false);
    expect(find.byKey(const Key('camera-preview')), findsNothing);
    expect(find.text('Face capture cannot continue without permission to use the camera.'), findsOneWidget);
    expect(find.text('Close'), findsOneWidget);
  });

  testWidgets('a refused Android permission also keeps the camera off', (tester) async {
    platform = FakeLivenessPlatform(permissionGranted: false);
    await open(tester);

    await allow(tester);

    expect(platform.calls, contains('requestCameraPermission'));
    expect(platform.startArgs!['consentGranted'], false);
    expect(find.byKey(const Key('camera-preview')), findsNothing);
  });

  testWidgets('shows the session: preview, prompt, step and countdown, attempts', (tester) async {
    await open(tester);
    await allow(tester);

    expect(platform.startArgs, {
      'workflow': LivenessWorkflow.operatorAuthentication,
      'subjectKey': '110011',
      'consentGranted': true,
      'language': 'en',
      'consentNoticeVersion': 'camera-notice-v1',
    });
    expect(find.byKey(const Key('camera-preview')), findsOneWidget);

    platform.emit(update('ACTIVE_CHALLENGE', 'Please turn your head to the left',
        guidance: 'Please continue', challenge: 'TURN_LEFT', step: 'Challenge 1 of 2', timeLeft: 'Time left: 6 s'));
    await tester.pump();

    expect(find.text('Please turn your head to the left'), findsOneWidget);
    expect(find.text('Please continue'), findsOneWidget);
    expect(find.text('Challenge 1 of 2 · Time left: 6 s'), findsOneWidget);
    expect(find.text('Attempt 1 of 3'), findsOneWidget);
    expect(find.text('Offline'), findsOneWidget);
    expect(find.text('Checked on this device · works offline'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byKey(const Key('liveness-retry'))).onPressed, isNull);
  });

  testWidgets('after a failed attempt: tips, then Retry', (tester) async {
    await open(tester);
    await allow(tester);

    platform.emit(update('ATTEMPT_FAILED', 'We could not verify face liveness. Please try again.',
        tips: ['Improve lighting', 'Look directly at the camera'], retryAvailable: true));
    await tester.pump();

    expect(find.text('• Improve lighting'), findsOneWidget);
    expect(find.text('Press "Retry" to try again.'), findsOneWidget);
    await tester.tap(find.byKey(const Key('liveness-retry')));
    expect(platform.calls.last, 'retry');
  });

  testWidgets('an accepted session closes by itself with the verified face, never showing the score',
      (tester) async {
    await open(tester);
    await allow(tester);
    final jpeg = Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xD9]);

    platform.emit(update('PASSED', 'Good, face captured successfully', attempt: null, progress: 1));
    platform.emit({
      'eventType': 'complete', 'sessionId': 's1', 'outcome': 'PASSED', 'accepted': true,
      'passiveScore': 87.5, 'capturedJpeg': jpeg, 'attempts': 1,
    });
    await tester.pump();

    expect(find.text('Good, face captured successfully'), findsOneWidget);
    expect(find.textContaining('87'), findsNothing);
    expect(closedWith, isNull);

    await tester.pumpAndSettle(const Duration(seconds: 2));
    expect(closedWith!.accepted, isTrue);
    expect(closedWith!.capturedJpeg, jpeg);
    expect(closedWith!.passiveScore, 87.5, reason: 'for the caller\'s records, not the screen');
  });

  testWidgets('cancel ends the session and closes with its result', (tester) async {
    await open(tester);
    await allow(tester);
    platform.emit(update('PASSIVE_CHECK', 'Checking face liveness…'));
    await tester.pump();

    await tester.tap(find.byKey(const Key('liveness-cancel')));
    await tester.pump();
    expect(platform.calls.last, 'cancel');
    platform.emit({'eventType': 'complete', 'sessionId': 's1', 'outcome': 'CANCELLED', 'accepted': false});
    await tester.pumpAndSettle();

    expect(closedWith!.outcome, 'CANCELLED');
    expect(find.byType(LivenessCaptureScreen), findsNothing);
  });

  testWidgets('a lockout says when it ends', (tester) async {
    await open(tester, language: 'fr');
    await allow(tester);

    platform.emit({
      'eventType': 'complete', 'sessionId': 's1', 'outcome': 'FAILED', 'accepted': false,
      'failureReason': 'LOCKED_OUT', 'requiredAction': 'BLOCK',
      'lockedUntil': DateTime(2026, 10, 30, 10, 30).millisecondsSinceEpoch,
    });
    await tester.pump();

    expect(find.textContaining("Bloqué jusqu'à 10:30"), findsOneWidget,
        reason: 'the MessageFormat apostrophe is undone');
  });

  testWidgets('Arabic lays the texts out right to left but never flips the preview', (tester) async {
    platform = FakeLivenessPlatform(rtl: true);
    await open(tester, language: 'ar');
    await allow(tester);
    platform.emit(update('PASSIVE_CHECK', 'جارٍ التحقق'));
    await tester.pump();

    expect(Directionality.of(tester.element(find.byKey(const Key('liveness-message')))), TextDirection.rtl);
    expect(Directionality.of(tester.element(find.byKey(const Key('camera-preview')))), TextDirection.ltr);
  });

  testWidgets('events from another session are ignored', (tester) async {
    await open(tester);
    await allow(tester);

    platform.emit({...update('PASSIVE_CHECK', 'stale'), 'sessionId': 'other'});
    platform.emit({'eventType': 'complete', 'sessionId': 'other', 'outcome': 'PASSED', 'accepted': true});
    await tester.pump(const Duration(seconds: 2));

    expect(find.text('stale'), findsNothing);
    expect(closedWith, isNull);
  });
}
