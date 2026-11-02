import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'models.dart';

/// Face liveness on the device. [MethodChannelLivenessPlatform] talks to the native plugin; tests substitute a
/// fake.
abstract class LivenessPlatform {
  /// Loads the engine (a few seconds; do it once, e.g. at login screen start).
  ///
  /// [config] is the client's `mosip.registration.liveness.*` settings (the synced global parameters); the
  /// built-in defaults apply when null. With [trustedKeys] (PEM public keys) the engine runs only on a signed model
  /// package. [simulated] uses a simulated camera and person, for demos and tests without a camera.
  ///
  /// With [modelUpdateUrl] (an https server publishing signed packages; needs [trustedKeys]) newer models are
  /// fetched every [modelSyncIntervalMinutes] while the device is online, and used from the next start.
  Future<Map<String, Object?>> initialize({
    bool simulated = false,
    Map<String, String>? config,
    List<String>? trustedKeys,
    String lensFacing = 'FRONT',
    String? modelUpdateUrl,
    int? modelSyncIntervalMinutes,
  });

  /// Screen texts in [language] (ISO 639-1/639-3, e.g. `en`, `fra`, `ara`), available before a session starts.
  Future<LivenessLabels> labels(String language);

  Future<bool> requestCameraPermission();

  /// Starts a session. With [consentGranted] false the session ends at once, audited, without opening the camera.
  Future<SessionStart> start({
    required LivenessWorkflow workflow,
    required String? subjectKey,
    required bool consentGranted,
    required String language,
    required String consentNoticeVersion,
  });

  Future<void> retry();

  Future<void> cancel();

  /// Simulated mode only: who or what is in front of the camera (LIVE, PRINTED_PHOTO, SCREEN_DISPLAY,
  /// VIDEO_REPLAY, NO_FACE, TWO_FACES).
  Future<void> simulate(String presentation);

  Future<void> dispose();

  /// Liveness performance on this device since [initialize]: `avgFrameProcessingMs`,
  /// `avgChallengeCompletionMs`, `retryRate`, `failureRate` and counts. Null before [initialize].
  Future<Map<String, Object?>?> metrics();

  /// Checks the model update server now: `status` is UPDATED, UP_TO_DATE, OFFLINE, REJECTED or SKIPPED_OFFLINE.
  Future<Map<String, Object?>> syncModels();

  /// Updates, results, audit records, model sync results and camera errors.
  Stream<LivenessEvent> get events;

  /// The live camera preview, mirrored like a selfie.
  Widget buildPreview(BuildContext context);
}

class MethodChannelLivenessPlatform implements LivenessPlatform {
  MethodChannelLivenessPlatform();

  @visibleForTesting
  final MethodChannel methods = const MethodChannel('mosip_face_liveness');

  @visibleForTesting
  final EventChannel eventChannel = const EventChannel('mosip_face_liveness/events');

  late final Stream<LivenessEvent> _events = eventChannel
      .receiveBroadcastStream()
      .map((e) => LivenessEvent.fromMap(e as Map<Object?, Object?>))
      .where((e) => e != null)
      .cast<LivenessEvent>()
      .asBroadcastStream();

  @override
  Future<Map<String, Object?>> initialize({
    bool simulated = false,
    Map<String, String>? config,
    List<String>? trustedKeys,
    String lensFacing = 'FRONT',
    String? modelUpdateUrl,
    int? modelSyncIntervalMinutes,
  }) async {
    final info = await methods.invokeMapMethod<String, Object?>('initialize', {
      'mode': simulated ? 'simulated' : 'camera',
      'config': config,
      'trustedKeys': trustedKeys,
      'lensFacing': lensFacing,
      'modelUpdateUrl': ?modelUpdateUrl,
      'modelSyncIntervalMinutes': ?modelSyncIntervalMinutes,
    });
    return info ?? const {};
  }

  @override
  Future<LivenessLabels> labels(String language) async =>
      LivenessLabels(await methods.invokeMapMethod<Object?, Object?>('labels', {'language': language}) ?? const {});

  @override
  Future<bool> requestCameraPermission() async =>
      await methods.invokeMethod<bool>('requestCameraPermission') ?? false;

  @override
  Future<SessionStart> start({
    required LivenessWorkflow workflow,
    required String? subjectKey,
    required bool consentGranted,
    required String language,
    required String consentNoticeVersion,
  }) async {
    final response = await methods.invokeMapMethod<Object?, Object?>('start', {
      'workflow': workflow.wireName,
      'subjectKey': subjectKey,
      'consentGranted': consentGranted,
      'language': language,
      'consentNoticeVersion': consentNoticeVersion,
    });
    return SessionStart.fromMap(response!);
  }

  @override
  Future<void> retry() => methods.invokeMethod('retry');

  @override
  Future<void> cancel() => methods.invokeMethod('cancel');

  @override
  Future<void> simulate(String presentation) => methods.invokeMethod('simulate', {'presentation': presentation});

  @override
  Future<void> dispose() => methods.invokeMethod('dispose');

  @override
  Future<Map<String, Object?>?> metrics() => methods.invokeMapMethod<String, Object?>('metrics');

  @override
  Future<Map<String, Object?>> syncModels() async =>
      await methods.invokeMapMethod<String, Object?>('syncModels') ?? const {};

  @override
  Stream<LivenessEvent> get events => _events;

  @override
  Widget buildPreview(BuildContext context) => const AndroidView(
        viewType: 'mosip_face_liveness/preview',
        creationParamsCodec: StandardMessageCodec(),
      );
}
