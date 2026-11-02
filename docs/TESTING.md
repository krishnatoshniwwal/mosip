# Test Cases and Results

**308 automated tests, all passing:** 292 Java tests across every module, and 16 Flutter tests for the Android
capture screen and channel. Beyond those, the system was tested by hand on a laptop webcam, MOSIP's mock SBI
device and an Android phone, and measured with an ISO/IEC 30107-3 evaluation.

## How to run them

```powershell
.\build.ps1                      # compiles everything and runs the 292 Java tests (JUnit 5); no Maven needed
./build.sh                       # the same on macOS, Linux or Git Bash; or: mvn verify
cd liveness-flutter; flutter test   # the 16 Flutter tests; no phone needed
```

Most tests drive a liveness session on a **virtual clock** with a scripted, simulated person (live, printed
photo, screen, video replay, deepfake through a virtual camera, nobody, two people, beside the oval, wearing a
mask...), so every edge case runs the same way every time, in milliseconds, without a camera. Engine tests run
the real OpenCV image checks on synthetic images.

## Results

| Suite | Tests | Result |
|---|---|---|
| Java, all modules (`.\build.ps1`) | 292 | **292 passed**, 0 failed |
| Flutter (`flutter test`) | 16 | **16 passed**, 0 failed |

## Test cases by area

Each row lists what the tests check, including the edge cases. The test class names are in brackets.

### Liveness decision

| Area | What is tested | Tests |
|---|---|---|
| Liveness session (`LivenessSessionTest`) | A confident live person passes on the passive check alone. A low passive score triggers random challenges that a live person completes. `active.always` forces challenges. An unresponsive person times out and uses up the retries. A printed photo is rejected by PAD with a generic message. A video replay that fools the passive check is stopped by the challenges. A head turn the wrong way fails at once. A second face during a challenge fails. No face, and poor quality, time out with the right guidance. Device faults don't use up retries. Challenges the engine can't run end as a configuration error. Passive-only policy. Liveness switched off. Cancel. A retry gets a different challenge sequence. Audit records carry no biometric data. | 17 |
| Attack rule (`PadAttackWindowTest`) | An attack flagged on every frame is rejected after 5 frames. Stray flagged frames (4 of any 20) never add up over a long session. Old frames leave the window. Invalid settings are refused. | 4 |
| Capture oval (`CapturePositionTest`, `CaptureAreaTest`) | A face beside the oval is never captured and gets "move into the oval", even when the model calls it an attack. An attack inside the oval is still rejected. Stray attack frames on a real face don't reject it. Too far, too close. Moving into the oval starts the check. Leaving the oval during a challenge fails. The oval's geometry is configurable per workflow and validated. | 15 |
| Challenges (`ChallengeSelectorTest`, `ActionDetectorTest`) | Challenges are distinct, unpredictable, never repeat the last sequence, and only use what the engine supports. Blink needs open-closed-open; eyes that never close, or are closed from the start, don't count. A smile must rise above the person's own baseline and hold. A head turn is measured from the starting pose in the requested direction; the opposite direction contradicts. Look up and down. | 14 |
| Masks and sunglasses (`OcclusionHandlingTest`, `OcclusionAnalyzerTest`) | The person is asked to uncover and then passes. Persistent masks fall back to challenges that don't need the mouth; sunglasses never get a blink challenge. A covered resident face is never captured. A beard is not a mask. A darker skin tone is judged against itself. | 14 |
| Brute force (`RateLimitTest`) | Lockout after too many failures in the time window; old failures forgotten; lockout survives a restart; the store holds only a hash of the user ID; repeated spoofing across sessions locks out; device faults never count. | 6 |

### Attacks

| Area | What is tested | Tests |
|---|---|---|
| Screen replay cues (`ReplayAttackDetectorTest`) | Moiré from a screen's pixel grid, flat emitted light, and a phone bezel around the face are detected. A naturally lit face raises no cue, and JPEG block patterns are not mistaken for moiré. How the cues combine with the model's verdict. | 9 |
| Injection attacks (`InjectionDetectionTest`, `StreamIntegrityMonitorTest`, `CameraSourceTest`, `CameraSourceCheckTest`) | A deepfake fed through a virtual camera fools PAD and challenges when injection detection is off, and is rejected from its frames when it's on. A real camera with real sensor noise is never flagged, even when heavily denoised. Repeated frames are flagged. Virtual cameras (OBS, ManyCam...) are recognised by name and type on Windows and macOS and block the session before the camera opens. | 24 |
| Capture binding (`LivenessGateTest`) | The face is captured only after liveness passes, and only if the same person stays. A photo swapped in during the capture, a second person, the person leaving or moving away are all rejected. Binding failures count towards the lockout. Slow and failing captures, cancel. | 12 |
| Signed evidence (`LivenessEvidenceTest`, `RegClientEvidenceTest`) | Evidence verifies for the same face and registration (ES256 and RS256). A face swapped after liveness, evidence copied from another registration, tampered, foreign or future-dated evidence are all refused. Failed runs produce evidence for manual review. | 11 |

### Models, devices and integration

| Area | What is tested | Tests |
|---|---|---|
| Signed model packages (`ModelRepositoryTest`, `ModelSyncTest`) | A tampered model file, unlisted file, edited manifest or foreign key is rejected. Key rotation. An older package can't be replayed, even after packages are deleted. A failed install leaves the active model untouched; tampering after install is caught at start-up; explicit rollback. Online sync: a forged or replayed package is refused before any model is downloaded, an unreachable server changes nothing, oversized files are cut off, plain HTTP only for local tests. | 27 |
| ONNX engine (`MiniFasNetEnsembleTest`, `FaceMeshAnalyzerTest`, `HeadPoseEstimatorTest`, `InferenceTargetTest`) | Face crops near the frame edges. Eye openness, smile, head pose (sliding sideways or tilting is not a turn). GPU is used only when it is faster *and* gives the same output as the CPU. | 26 |
| Devices (`SbiDiscoveryTest`, `SbiFaceStreamDeviceTest`, `OpenCvCameraDeviceTest`, `DeviceConformanceSuiteTest`, `MdsStreamDeviceTest`) | SBI discovery and MJPEG streaming, busy and unreachable devices, chunked streams. Webcam stop and restart, failed reads. The conformance suite passes the mock device and fails a misbehaving one on exactly the right checks. | 32 |
| Service, consent and config (`FaceLivenessServiceTest`, `CameraConsentTest`, `LivenessConfigTest`) | Disconnect mid-session and retry, unavailable devices, corrupt frames. The camera is never opened without consent; a refusal is audited. Defaults are secure; unknown or invalid settings are refused, so a typo can't weaken the policy. | 21 |
| Registration Client (`RegClientIntegrationTest`, `RegClientConnectivityTest`) | Settings come from the client's synced global parameters; audit events reach the client's audit manager without biometric details; a failing audit store doesn't break liveness; model sync only while online. | 9 |
| Messages (`LivenessMessagesTest`) | Every one of the 9 languages translates every message. MOSIP language codes. Arabic is right-to-left. Security failures stay generic in every language. | 7 |
| Android (`FlutterLivenessBridgeTest`, `CameraXFaceCaptureDeviceTest`, `Nv21RotatorTest`, `YuvToNv21ConverterTest`, `AndroidEdgeLivenessEngineTest`) | The Flutter bridge's messages, the screen texts in the session's language, a new start replacing an abandoned session, results with and without the face. CameraX frame delivery, frame-rate limit, denied permission. Frame rotation, mirroring and colour conversion. Out-of-memory becomes an engine error. | 25 |
| Evaluation tools (`PadEvaluationTest`, `PadEvaluationToolTest`, `DatasetLayoutTest`, `ClipWriterTest`) | APCER per attack type, BPCER, Wilson intervals, non-responses never counted as acceptances. Only frames with one face inside the oval are judged. Recorded clips are pseudonymous, never overwrite each other, and an aborted clip leaves nothing behind. | 19 |
| Flutter screen and channel (`liveness_capture_screen_test.dart`, `liveness_platform_test.dart`) | Asks before turning the camera on; a refusal keeps it off. Shows the preview, instruction, step, countdown and attempts. Tips and Retry after a failure. An accepted session closes with the face and never shows the score. A lockout says when it ends. Arabic is right-to-left but the preview isn't flipped. Events from another session are ignored. Channel arguments and events match the Java bridge. | 16 |

## Manual and device testing

| Test | Setup | Result |
|---|---|---|
| Live webcam | Laptop webcam, `.\build.ps1 -Run` | Real people pass; photos on a phone screen are rejected |
| MOSIP mock SBI | MOSIP's `mosip-mock-services` MockMDS, `.\build.ps1 -Tool "interop --device sbi:auto"` | Discovered and streamed at about 22 fps; all conformance checks pass. The mock streams a still photo, which the app correctly rejects as an attack |
| Registration Client screens | `.\build.ps1 -Run -RegClient` | Operator login, resident capture and supervisor approval, each with its own policy |
| Android phone | vivo V2253, Android 15, front camera, release APK | All three workflows run with the real models; the same prompts, oval and messages as desktop |
| ISO/IEC 30107-3 evaluation | 41 recorded clips, `evaluate` tool | No screen attack accepted (0 of 12); 17% of real people rejected (5 of 29), 4 of them for recording problems. Details and limits in [SUBMISSION.md](SUBMISSION.md#isoiec-30107-3-evaluation-preliminary) |
| Speed | Laptop CPU | About 34 ms per frame in the evaluation (median), 50–55 ms with every model in the live app |
