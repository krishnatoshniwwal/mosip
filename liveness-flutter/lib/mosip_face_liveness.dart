/// Face liveness and presentation attack detection for the MOSIP Android Registration Client.
///
/// ```dart
/// final liveness = MethodChannelLivenessPlatform();
/// await liveness.initialize(config: syncedLivenessSettings);   // once, e.g. when the app starts
///
/// final result = await LivenessCaptureScreen.open(context,
///     platform: liveness,
///     workflow: LivenessWorkflow.operatorAuthentication,
///     title: 'Login with face',
///     subjectKey: userId,
///     language: appLanguage);
/// if (result.accepted) {
///   // match / store result.capturedJpeg: the face that passed liveness
/// } else {
///   // follow result.requiredAction: BLOCK, REQUIRE_SUPERVISOR_OVERRIDE or ALLOW_ALTERNATE_AUTH
/// }
/// ```
library;

export 'src/capture_oval_painter.dart' show CaptureOvalPainter, LivenessColors;
export 'src/liveness_capture_screen.dart' show LivenessCaptureScreen;
export 'src/liveness_platform.dart' show LivenessPlatform, MethodChannelLivenessPlatform;
export 'src/models.dart';
