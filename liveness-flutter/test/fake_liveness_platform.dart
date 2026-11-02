import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:mosip_face_liveness/mosip_face_liveness.dart';

/// Records what the screen asks for and lets a test play the native side's events.
class FakeLivenessPlatform implements LivenessPlatform {
  FakeLivenessPlatform({this.permissionGranted = true, this.rtl = false});

  final bool permissionGranted;
  final bool rtl;
  final events_ = StreamController<LivenessEvent>.broadcast();
  final calls = <String>[];
  Map<String, Object?>? startArgs;

  @override
  Stream<LivenessEvent> get events => events_.stream;

  void emit(Map<Object?, Object?> map) => events_.add(LivenessEvent.fromMap(map)!);

  @override
  Future<LivenessLabels> labels(String language) async {
    calls.add('labels:$language');
    return LivenessLabels({
      'language': language,
      'rtl': rtl,
      'consentNoticeVersion': 'camera-notice-v1',
      'consentTitle': 'Camera permission',
      'consentText': 'To capture your face this application needs the camera.',
      'allow': 'Allow',
      'deny': "Don't allow",
      'retry': 'Retry',
      'cancel': 'Cancel',
      'close': 'Close',
      'pressRetry': 'Press "Retry" to try again.',
      'onDevice': 'Checked on this device · works offline',
      'online': 'Online',
      'offline': 'Offline',
      'consentFailed': 'Face capture cannot continue without permission to use the camera.',
      'lockedUntil': "Bloqué jusqu''à {0}",
    });
  }

  @override
  Future<bool> requestCameraPermission() async {
    calls.add('requestCameraPermission');
    return permissionGranted;
  }

  @override
  Future<SessionStart> start({
    required LivenessWorkflow workflow,
    required String? subjectKey,
    required bool consentGranted,
    required String language,
    required String consentNoticeVersion,
  }) async {
    calls.add('start');
    startArgs = {
      'workflow': workflow,
      'subjectKey': subjectKey,
      'consentGranted': consentGranted,
      'language': language,
      'consentNoticeVersion': consentNoticeVersion,
    };
    return SessionStart.fromMap({
      'sessionId': 's1',
      'captureArea': {'enabled': true, 'centerX': 0.5, 'centerY': 0.48, 'radiusX': 0.22, 'radiusY': 0.37},
    });
  }

  @override
  Future<void> retry() async => calls.add('retry');

  @override
  Future<void> cancel() async => calls.add('cancel');

  @override
  Future<void> simulate(String presentation) async => calls.add('simulate:$presentation');

  @override
  Future<void> dispose() async => calls.add('dispose');

  @override
  Future<Map<String, Object?>> initialize({
    bool simulated = false,
    Map<String, String>? config,
    List<String>? trustedKeys,
    String lensFacing = 'FRONT',
    String? modelUpdateUrl,
    int? modelSyncIntervalMinutes,
  }) async =>
      const {};

  @override
  Future<Map<String, Object?>?> metrics() async => null;

  @override
  Future<Map<String, Object?>> syncModels() async => const {'status': 'UP_TO_DATE'};

  @override
  Widget buildPreview(BuildContext context) => const SizedBox(key: Key('camera-preview'));
}

Map<Object?, Object?> update(String state, String message,
        {String? guidance,
        List<String> tips = const [],
        String? challenge,
        String? step,
        String? timeLeft,
        String? attempt = 'Attempt 1 of 3',
        double progress = 0.3,
        bool retryAvailable = false}) =>
    {
      'eventType': 'update',
      'sessionId': 's1',
      'workflow': 'OPERATOR_AUTHENTICATION',
      'state': state,
      'message': message,
      'messageCode': 'X',
      'messageText': message,
      'guidanceText': guidance,
      'tipsText': tips,
      'challenge': challenge,
      'stepText': step,
      'timeLeftText': timeLeft,
      'attemptText': attempt,
      'progress': progress,
      'retryAvailable': retryAvailable,
    };
