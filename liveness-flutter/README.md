# mosip_face_liveness

Face liveness and presentation attack detection for the **MOSIP Android Registration Client** (Flutter on a
Java layer). It runs the same liveness core and engine as the desktop client, on the device:

- **Engine:** the desktop `OnnxLivenessEngine` unchanged (YuNet, MiniFASNet, MediaPipe Face Mesh and Iris),
  on OpenCV's and ONNX Runtime's Android builds.
- **Camera:** CameraX. The preview is mirrored; analysis frames are upright and unmirrored, so "turn left" is
  judged the same way as on desktop. At most 10 frames a second are analysed.
- **Screen:** `LivenessCaptureScreen`, built to [docs/UI_UX.md](../docs/UI_UX.md) section 8. It shows the
  camera notice first, then the preview with the capture oval, one instruction at a time, the challenge step and
  countdown, attempts, tips, and Retry / Cancel. Texts come from the same nine translation bundles as the
  desktop dialog, with Arabic laid out right to left.
- **Models:** bundled in the APK. With trusted keys, they are installed as a signed package through the same
  `ModelRepository` as on desktop (signature, hashes, rollback protection, re-verified at every start).

Requires Android 8.0 (API 26).

## Use it in the client

```dart
final liveness = MethodChannelLivenessPlatform();
await liveness.initialize(config: syncedLivenessSettings, trustedKeys: [modelReleaseKeyPem]);

final result = await LivenessCaptureScreen.open(context,
    platform: liveness,
    workflow: LivenessWorkflow.operatorAuthentication,
    title: 'Login with face',
    subjectKey: userId,          // failed attempts and lockouts are counted per user
    language: appLanguage,       // e.g. 'eng', 'fra', 'ara'
    online: isOnline);           // badge only; liveness is the same offline
if (result.accepted) {
  // result.capturedJpeg is the face that passed liveness: match or store exactly this image
} else {
  // result.requiredAction: BLOCK, REQUIRE_SUPERVISOR_OVERRIDE or ALLOW_ALTERNATE_AUTH
}
```

`config` takes the client's `mosip.registration.liveness.*` settings as strings. These are the same keys as
[config/liveness.properties](../config/liveness.properties), with per-workflow policies. Unknown or invalid
keys make `initialize` fail, so a mistyped setting can't weaken the policy. Without `trustedKeys` the bundled
models are loaded unverified, which is for development only.

## Build and test

Run `./build.sh` or `.\build.ps1` once in the repository root first; it downloads the models the APK bundles.

```bash
cd liveness-flutter
flutter test                                  # screen and channel tests (no device needed)
cd example
flutter run                                   # on a phone or emulator; "Simulated camera" needs no camera
flutter build apk --release --split-per-abi   # arm64 APK is about 80 MB
```

The native side is in `android/`. Its sources, dependencies and models come from
[liveness-android/liveness-library.gradle](../liveness-android/liveness-library.gradle), which the standalone
AAR build applies too. If the repository is in a OneDrive-synced folder, Gradle can fail with "Unable to delete
directory". Pause syncing, or build from a copy outside OneDrive.
