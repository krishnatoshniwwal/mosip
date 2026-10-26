# MOSIP Registration Client: Face Liveness and Presentation Attack Detection

MOSIP Decode, problem statement 04. This adds face liveness and PAD to resident face capture, operator
authentication and supervisor authentication, using a **hybrid passive → active** approach:

1. **Passive:** the incoming face frames are scored without asking the person to do anything.
2. If the score is below the configured threshold, **active challenges** are issued automatically
   ("Please blink", "Please turn your head to the left", …). Their number and order are random.
3. **PAD** and continuity checks run on every frame throughout. A face is accepted, or an
   authentication completes, only when every check passes.
4. Only a face **inside the on-screen oval** counts. A face elsewhere in the camera's view is ignored, and
   the person is told to move into the oval, closer or back.

Everything runs locally, so it behaves the same online and offline. Devices and liveness technologies plug
in behind interfaces, so no vendor is hard-wired.

## Status (v0.1)

| Done | Not yet |
|---|---|
| Vendor-neutral device and engine interfaces | Look up/down (needs calibrated pitch) |
| Blink (MediaPipe Iris eyelid contour) and smile (MediaPipe Face Mesh) challenges | |
| Enforced capture oval (position + distance), drawn from the same config | Calibrating replay/occlusion thresholds on real attack data |
| Replay attack cues: moiré, lighting uniformity, bezel/frame edges | Native-speaker review of translations |
| Occlusion handling (mask, scarf, sunglasses) with region-safe challenge fallback | |
| Cross-session rate limiting with lockout persisted across restarts | |
| Localised prompts: English, French, Arabic (RTL), Hindi, Kannada, Tamil, Filipino, Amharic, Sinhala | |
| Signed model packages: ECDSA manifest, rollback protection, atomic install, tamper check at start | |
| ISO/IEC 30107-3 evaluation harness: APCER per species, BPCER, BPCER10/20/100, DET, confidence intervals | Evaluation on data collected from target devices |
| Device interoperability conformance suite + engine performance benchmark | Running it on each supported vendor device |
| Live webcam engine: YuNet face detection + landmark head pose (turn left/right challenges) | Threshold calibration on recorded live/spoof sessions |
| MiniFASNet anti-spoofing (2-model ONNX ensemble, matches upstream output exactly) | |
| Liveness state machine: passive, PAD, random challenges, retries, failure actions | Applying the controller changes in `mosip/registration-client` ([guide](docs/REGCLIENT_INTEGRATION.md)) |
| Desktop Registration Client integration (`liveness-regclient`): JavaFX capture dialog for resident capture, operator login and supervisor auth; MDS stream adapter; config from synced global params; audit into `AuditManagerService` | |
| Capture binding: liveness gates the MDS rCapture / face match and watches the stream until it returns | |
| Per-workflow configuration with strict validation | Android adapter + Flutter screen |
| SBI device discovery + `STREAM /stream` adapter, tested against the MOSIP mock SBI | A passing session on real L0/L1 hardware |
| Mock L0/L1 device + simulated subject and engine (live, photo, screen, replay, …) | |
| Audit events (no biometric data) and anonymous metrics | |
| Reference capture UI (Swing) with diagnostic mode | |
| Explicit camera consent before the device is opened (enforced by the service, audited, translated) | |
| 191 automated tests | |

The live engine (`liveness-onnx-engine`) detects faces with OpenCV's YuNet model and measures head turns
from its five landmarks. Smile comes from the MediaPipe Face Mesh model and blink from the MediaPipe Iris
model's eyelid contour. Both models run unchanged through OpenCV's TFLite reader, so the challenges are
blink, smile, turn left and turn right (look up/down stay off until pitch is calibrated). Anti-spoofing uses the
MiniFASNet models in `models/` (Apache-2.0, converted with `tools/convert_minifasnet.py`; see
`models/NOTICE.md`). The simulated engine (`-Sim`) reports what a model would
measure and does not analyse real pixels.

## Run it

Needs JDK 11+. No Maven required: the scripts download JUnit, OpenCV, ONNX Runtime and the YuNet model
(`models/`, checksum-verified) on first run. Maven users must run a script once to fetch the model, or point
`-Dmosip.liveness.models=<dir>` at a folder containing `face_detection_yunet_2023mar.onnx`.

```powershell
.\build.ps1          # compile + 191 tests
.\build.ps1 -Run     # launch LIVE webcam capture & real liveness
.\build.ps1 -Run -Sim # launch simulated desktop demo (mock device)
.\build.ps1 -Run -Device sbi:auto   # live capture from an SBI face device (MDS on 127.0.0.1:4501-4600)
.\build.ps1 -Run -RegClient        # Registration Client screens (operator login, resident face, supervisor) with liveness
.\build.ps1 -Run -RegClient -Sim   # the same with the mock device; try a photo swapped in during the capture
```
```bash
./build.sh --run     # macOS / Linux / Git Bash
mvn verify           # with Maven
```

In the demo, pick a workflow and press **Start**. Use the *Device simulator* bar to choose who or what is in
front of the camera (live person, low passive score, printed photo, screen, video replay, nobody, two
people, beside the oval, too far, wearing a mask, wearing sunglasses) and how they respond (follow / ignore / wrong action / manual with keys **B S ← → ↑ ↓**). Tick
**Diagnostic mode** to see the audit trail and metrics. Both demos have a **Language** picker for the
translated prompts. The demo reads `config/liveness.properties`.

## Tools

```bash
# Record the evaluation dataset with the webcam (one clip per click, written in the layout evaluate reads)
./build.sh tool record dataset --device webcam:0 --seconds 5

# ISO/IEC 30107-3 PAD evaluation: dataset/bona_fide/..., dataset/attack/<species>/... (images, videos)
./build.sh tool evaluate dataset --out pad-evaluation --passive-threshold 70

# Device interoperability + engine performance (writes interop-report.md)
./build.sh tool interop --device webcam:0 --disconnect manual   # asks before turning the camera on; --yes for CI
./build.sh tool interop --device sbi:auto        # or sbi:127.0.0.1:4501[:subId]

# Signed model packages (release side: keygen, sign; client side: install, status, rollback)
./build.sh tool models keygen release-private.pem release-public.pem
./build.sh tool models sign models-v2/ release-private.pem --version 1.3.0 --sequence 2
./build.sh tool models install models-v2/ /var/lib/mosip/liveness-models release-public.pem
```

On Windows: `.\build.ps1 -Tool "interop --device webcam:0"`. The live demo runs only on a verified package
when started with `-Dmosip.liveness.modelRepo=<repo> -Dmosip.liveness.trustedKey=<public.pem>`.

### Testing with the MOSIP mock SBI

The SBI path can be tested without hardware, using MOSIP's own mock device service:

```bash
git clone https://github.com/mosip/mosip-mock-services.git      # on Windows: git config core.longpaths true
cd mosip-mock-services/MockMDS && mvn clean install -Dmaven.test.skip=true -Dgpg.skip=true
cd target && java -cp "mock-mds-<version>.jar;lib/*" io.mosip.mock.sbi.test.TestMockSBI \
    "mosip.mock.sbi.device.purpose=Registration" "mosip.mock.sbi.biometric.type=Biometric Device"   # ':' on Linux/macOS
```

Then run `.\build.ps1 -Tool "interop --device sbi:auto --yes"` (conformance report) or `.\build.ps1 -Run -Device sbi:auto`.
The mock streams an annotated still photo, so the live app correctly rejects it as a presentation attack. This
tests the integration, not a passing session.

### Collecting the evaluation dataset

1. Get each participant's written consent first. Give them a pseudonymous ID (`S01`, `S02`, …) and never
   record names. The recorder only enables **Record** after you confirm consent, and asks again when the ID changes.
2. Run `.\build.ps1 -Tool "record dataset"`. For each participant, record several clips of each kind:
   - **Live person:** vary distance, lighting and glasses.
   - **Printed photo**, **photo on a screen**, **video replay** of that participant (masks optional).

   The window shows instructions for each kind and how many clips you have so far.
3. Clips land in `dataset/bona_fide/<ID>/` and `dataset/attack/<kind>/<ID>/`, with a log in `dataset/recordings.csv`.
   `dataset/` and `pad-evaluation/` are git-ignored: the clips are biometric data, so never commit or share them,
   and delete them when the evaluation is done.
4. Run `.\build.ps1 -Tool "evaluate dataset"` and read `pad-evaluation/report.md` to choose thresholds.

## Layout

```
liveness-core/                       pure Java 11 library, zero dependencies (Desktop + Android)
  config/      LivenessConfig, LivenessPolicy, FailureAction
  device/      FaceCaptureDevice, FaceFrame, PixelFormat, DeviceInfo   (device adapter pattern)
  device/sbi/  SbiDiscovery, SbiFaceStreamDevice, MjpegStreamReader    (MOSIP SBI discovery + stream)
  engine/      LivenessEngine, FrameAnalysis, FaceObservation, PadResult (liveness/PAD interface)
  challenge/   ChallengeSelector (SecureRandom), blink/smile/head-pose detectors
  session/     LivenessSession state machine, UserMessage, FailureReason, LivenessResult
  service/     FaceLivenessService, LivenessHandle (threading, backpressure, device lifecycle)
  audit/       AuditEvent(Type), AuditSink, LivenessMetrics
  mock/        MockFaceDevice, SimulatedSubject, SimulatedLivenessEngine
  i18n/        LivenessMessages + messages_<lang>.properties           (localised prompts, RTL)
  model/       ModelPackageVerifier, ModelRepository, ModelSigning      (signed model packages)
  evaluation/  PadMetrics, PadEvaluation                                (ISO/IEC 30107-3 metrics + report)
  conformance/ DeviceConformanceSuite, EngineBenchmark                  (interop + performance tests)
  gate/        LivenessGate, GateResult                                 (liveness before the client's own capture)
liveness-onnx-engine/                YuNet + MiniFASNet engine, ReplayAttackDetector, OcclusionAnalyzer
liveness-tools/                      CLI: record, evaluate, interop, models
liveness-desktop-demo/               reference face capture screen (Swing)
liveness-regclient/                  Desktop Registration Client integration: JavaFX dialog, MDS stream, config, audit
config/liveness.properties           sample per-workflow policy
docs/DESIGN.md                       technical design: architecture, flows, config, errors, security, ISO 30107
docs/REGCLIENT_INTEGRATION.md        controller-by-controller changes for mosip/registration-client
```

## Using it from a workflow

```java
FaceLivenessService service = new FaceLivenessService(
        LivenessConfig.load(Paths.get("liveness.properties")), engine, auditSink);

LivenessMessages text = LivenessMessages.forLanguage("fra");   // client language, ISO 639-3

// Show text.ui(UI_CONSENT_TEXT) with Allow / Don't allow first; the camera is opened only if allowed.
CameraConsent consent = allowed
        ? CameraConsent.granted(LivenessMessages.CONSENT_NOTICE_VERSION, text.language(), System.currentTimeMillis())
        : CameraConsent.declined(LivenessMessages.CONSENT_NOTICE_VERSION, text.language(), System.currentTimeMillis());

// Subject key = operator ID: failed attempts are rate limited per operator across sessions.
LivenessHandle h = service.start(LivenessWorkflow.OPERATOR_AUTHENTICATION, operatorId, consent, device, new LivenessListener() {
    public void onUpdate(LivenessUpdate u)   { /* show text.text(u.getMessage()), guidance, step, countdown, retry */ }
    public void onComplete(LivenessResult r) {
        if (r.isAccepted()) match(r.getCapturedFrame());          // face match on the verified frame
        else handle(r.getRequiredAction());                     // BLOCK / supervisor override / alternate auth
    }
});
// h.retry() when the user presses Retry; h.cancel() to abort
```

See [docs/DESIGN.md](docs/DESIGN.md) for the full design.
