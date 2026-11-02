# API Documentation

The solution is a library, not a web service. Liveness runs fully on the station or phone, with **no network
API and no authentication** for a liveness decision. It exposes:

1. a **Java API**, used by the desktop Registration Client and, on Android, by the plugin's native layer;
2. a **Flutter (Dart) API**, for the Android Registration Client;
3. **two plug-in interfaces**, to add a capture device or a liveness engine;
4. **file formats** for signed model updates and for the signed liveness evidence that goes in the
   registration packet;
5. **command-line tools**.

All settings are `mosip.registration.liveness.*` keys, documented in
[config/liveness.properties](../config/liveness.properties).

## 1. Java API

### Start a liveness check

```java
FaceLivenessService service = new FaceLivenessService(
        LivenessConfig.load(Paths.get("liveness.properties")),   // or the client's synced global parameters
        new OnnxLivenessEngine(modelDir),                        // the on-device engine
        auditSink);                                              // where audit events go

LivenessMessages text = LivenessMessages.forLanguage("fra");     // ISO 639-3 or 639-1 code

CameraConsent consent = CameraConsent.granted(
        LivenessMessages.CONSENT_NOTICE_VERSION, text.language(), System.currentTimeMillis());

LivenessHandle h = service.start(LivenessWorkflow.OPERATOR_AUTHENTICATION, operatorId, consent, device,
        new LivenessListener() {
            public void onUpdate(LivenessUpdate u)   { /* show text.text(u.getMessage()), guidance, countdown */ }
            public void onComplete(LivenessResult r) { /* see below */ }
        });
// h.retry() when the user presses Retry; h.cancel() to stop
```

| Parameter | Meaning |
|---|---|
| `workflow` | `RESIDENT_REGISTRATION`, `OPERATOR_AUTHENTICATION` or `SUPERVISOR_AUTHENTICATION`. Each has its own policy |
| `subjectKey` | Registration ID for a resident, user ID for an operator or supervisor. Failed attempts and lockouts are counted per key; only a hash is stored |
| `consent` | The camera is never opened unless consent was granted (when `consent.required=true`) |
| `device` | Any `FaceCaptureDevice`: SBI/MDS device, webcam, CameraX on Android, or the mock |

### Result: `LivenessResult`

| Method | Value |
|---|---|
| `isAccepted()` | true only when liveness passed |
| `getOutcome()` | `PASSED`, `FAILED`, `CANCELLED`, `LIVENESS_DISABLED` |
| `getCapturedFrame()`, `getCapturedFace()` | the verified frame and face (only when accepted): match or store exactly this |
| `getFailureReason()` | e.g. `PAD_ATTACK_DETECTED`, `INJECTION_DETECTED`, `CHALLENGE_FAILED`, `LIVENESS_BELOW_THRESHOLD`, `FACE_NOT_POSITIONED`, `MULTIPLE_FACES`, `LOCKED_OUT`, `CONSENT_DECLINED`, `DEVICE_UNAVAILABLE` (21 values) |
| `getRequiredAction()` | what the client must do after a failure: `BLOCK`, `REQUIRE_SUPERVISOR_OVERRIDE` or `ALLOW_ALTERNATE_AUTH` |
| `getPassiveScore()`, `getChallengesCompleted()`, `getAttempts()`, `getAttemptFailures()`, `getDurationMs()`, `getEngine()`, `getLockedUntil()` | details for audit and diagnostics; never shown to the user |

Messages shown to the user stay generic for security failures in every language, so an attacker can't tell
which check caught them.

### Verify, then capture (Registration Client)

`RegClientLiveness` wraps the service for the desktop client. It runs liveness first and calls the client's
existing capture (the MDS `rCapture` or face authentication) **only if liveness passed**, while it keeps
watching the stream so the face can't be swapped during the capture:

```java
RegClientLiveness liveness = new RegClientLiveness(config, engine, audit, rateLimitStoreFile);
GateHandle<Capture> h = liveness.verifyThenCapture(LivenessWorkflow.RESIDENT_REGISTRATION, rid, consent, device,
        () -> mdsRCapture(),                       // the client's existing capture call
        gateListener);                             // result: the capture, or why it was refused

String evidenceJws = liveness.issueEvidence(gateResult, bioValueBytes, rid);  // goes in the packet
```

Other methods: `setCameraSourceCheck` (refuse stations with a virtual camera), `setEvidenceSigner` (the
station's TPM key), `setConnectivity`, `startModelSync` / `syncModelsIfOnline`. The full integration, controller by
controller, is in [REGCLIENT_INTEGRATION.md](REGCLIENT_INTEGRATION.md).

## 2. Flutter (Dart) API: `mosip_face_liveness`

```dart
final liveness = MethodChannelLivenessPlatform();
await liveness.initialize(config: syncedLivenessSettings, trustedKeys: [modelReleaseKeyPem]);

final result = await LivenessCaptureScreen.open(context,
    platform: liveness,
    workflow: LivenessWorkflow.operatorAuthentication,
    title: 'Login with face',
    subjectKey: userId,
    language: 'eng',
    online: isOnline);
if (result.accepted) {
  // result.capturedJpeg: the face that passed liveness
} else {
  // result.requiredAction: BLOCK, REQUIRE_SUPERVISOR_OVERRIDE or ALLOW_ALTERNATE_AUTH
}
```

`LivenessCaptureScreen` is the ready-made screen (consent notice, preview with the oval, instructions, retry).
Clients that build their own screen use the platform methods directly:

| Method | Arguments | Returns |
|---|---|---|
| `initialize` | `simulated` (bool), `config` (settings as strings), `trustedKeys` (PEM; verifies the models), `lensFacing` (`FRONT`/`BACK`), `modelUpdateUrl`, `modelSyncIntervalMinutes` (default 360) | engine and model information. Unknown or invalid settings fail |
| `labels` | `language` | all screen texts, and whether the language is right-to-left |
| `requestCameraPermission` | – | whether Android granted the camera |
| `start` | `workflow`, `subjectKey`, `consentGranted`, `language`, `consentNoticeVersion` | `SessionStart`: session ID and the oval's position |
| `retry` / `cancel` | – | – |
| `metrics` | – | time per analysed frame, challenge completion time, retry and failure rates |
| `syncModels` | – | checks for a newer signed model package now |
| `simulate` | `presentation` (e.g. `LIVE`, `PRINTED_PHOTO`, `SCREEN_DISPLAY`) | simulated camera only |
| `dispose` | – | releases the camera and engine |

**Events** (`liveness.events`, a stream):

| Event | Fields |
|---|---|
| `LivenessUpdate` | `sessionId`, `state`, `messageCode`, `messageText`, `guidanceText`, `tips`, `challenge`, `stepText`, `timeLeftText`, `attemptText`, `progress`, `retryAvailable` (texts already translated) |
| `LivenessResult` | `sessionId`, `outcome`, `accepted`, `failureReason`, `requiredAction`, `attempts`, `durationMs`, `challengesCompleted`, `passiveScore`, `lockedUntil`, `capturedJpeg` |
| `LivenessAuditEvent` | `code`, `type`, `attempt` (no biometric data) |
| `ModelSyncEvent` | `status`, `detail`, `offered` |
| `CameraErrorEvent` | `message` |

On the wire, these are the MethodChannel `mosip_face_liveness`, the EventChannel `mosip_face_liveness/events`
and the platform view `mosip_face_liveness/preview` (camera preview). See
[liveness-flutter/README.md](../liveness-flutter/README.md).

## 3. Plug-in interfaces

| Interface | Methods | Implementations |
|---|---|---|
| `FaceCaptureDevice` | `info()`, `status()`, `open()`, `startStream(FrameListener)`, `stopStream()`, `close()` | SBI face devices (`sbi:auto` or `sbi:HOST:PORT`), MDS stream in the Registration Client, OpenCV webcam, CameraX on Android, mock device |
| `LivenessEngine` | `info()`, `analyze(FaceFrame)` → faces, liveness score, PAD verdict | `OnnxLivenessEngine` (YuNet, MiniFASNet, Face Mesh, Iris, replay cues), on desktop and Android |

A new device adapter can be checked with the conformance suite: `.\build.ps1 -Tool "interop --device <id>"`.

## 4. File formats

### Signed model package (offline media or an update server)

A folder with the model files plus:

| File | Content |
|---|---|
| `manifest.properties` | package name, version, `package.sequence` (must increase; older packages are refused), `engine.minVersion`, and the SHA-256 and size of every file |
| `manifest.sig` | ECDSA P-256 / SHA-256 signature of the manifest by the release key |

An update server just publishes this folder as static files over HTTPS. Clients fetch and check the manifest
and signature **before** downloading any model. See [DESIGN.md §7](DESIGN.md#7-security-and-privacy).

### Liveness evidence in the registration packet

A compact JWS (ES256 or RS256), signed with the station's TPM key and carried next to the face, for example as a
`LIVENESS_EVIDENCE` entry in the face BIR's `others` map. The payload holds the session ID, workflow, outcome,
passive score, challenges, attempts and failure reasons, engine and model version, station ID, time, and SHA-256
hashes of the exact face bytes and of the registration ID. It holds **no image or template**.

Server-side check:

```java
EvidenceVerification v = LivenessEvidenceJws.verify(jws, stationPublicKey, faceBytesInPacket, rid, Instant.now());
```

| Status | Meaning |
|---|---|
| `ACCEPTED` | liveness passed for exactly this face and registration |
| `LIVENESS_NOT_PASSED` | genuine record that liveness failed: manual review |
| `CAPTURE_MISMATCH` | the face was swapped after liveness: reject |
| `SUBJECT_MISMATCH` | evidence copied from another registration: reject |
| `BAD_SIGNATURE`, `MALFORMED`, `NOT_YET_VALID` | forged, tampered with or future-dated: reject |

## 5. Command-line tools

`.\build.ps1 -Tool "<command>"` on Windows, `./build.sh tool <command>` elsewhere.

| Command | Use |
|---|---|
| `record <dataset>` | record evaluation clips from the webcam (asks for consent first) |
| `evaluate <dataset> [--out DIR] [--config FILE] [--passive-threshold N] [--min-attack-frames N] [--attack-window N] [--capture-area on\|off]` | ISO/IEC 30107-3 report: APCER per attack type, BPCER, DET curve |
| `interop --device webcam:0\|sbi:auto\|mock` | device conformance and engine speed report |
| `models keygen\|sign\|verify\|install\|sync\|status\|rollback` | signed model packages |
| `evidence verify <evidence.jws> <station-public.pem> --capture <face.bin> --subject <RID>` | check a packet's liveness evidence |
