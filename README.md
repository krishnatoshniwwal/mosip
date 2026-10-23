# MOSIP Registration Client: Face Liveness and Presentation Attack Detection

MOSIP Decode, problem statement 04. This adds face liveness and PAD to resident face capture, operator
authentication and supervisor authentication, using a **hybrid passive → active** approach:

1. **Passive:** the incoming face frames are scored without asking the person to do anything.
2. If the score is below the configured threshold, **active challenges** are issued automatically
   ("Please blink", "Please turn your head to the left", …). Their number and order are random.
3. **PAD** and continuity checks run on every frame throughout. A face is accepted, or an
   authentication completes, only when every check passes.

Everything runs locally, so it behaves the same online and offline. Devices and liveness technologies plug
in behind interfaces, so no vendor is hard-wired.

## Status (v0.1)

| Done | Not yet |
|---|---|
| Vendor-neutral device and engine interfaces | Real CV/anti-spoof model engine (v0.2) |
| Liveness state machine: passive, PAD, random challenges, retries, failure actions | Wiring into the JavaFX Registration Client screens |
| Per-workflow configuration with strict validation | Android adapter + Flutter screen |
| SBI `STREAM /stream` (MJPEG) device adapter | Signed model updates |
| Mock L0/L1 device + simulated subject and engine (live, photo, screen, replay, …) | |
| Audit events (no biometric data) and anonymous metrics | |
| Reference capture UI (Swing) with diagnostic mode | |
| 48 automated tests | |

The simulated engine reports what a model would measure. It does **not** analyse real camera pixels. The real
engine is the next milestone and plugs into the same `LivenessEngine` interface.

## Run it

Needs JDK 11+. No Maven required (the scripts download the JUnit console launcher on first run).

```powershell
.\build.ps1          # compile + 48 tests
.\build.ps1 -Run     # launch LIVE webcam capture & real liveness
.\build.ps1 -Run -Sim # launch simulated desktop demo (mock device)
```
```bash
./build.sh --run     # macOS / Linux / Git Bash
mvn verify           # with Maven
```

In the demo, pick a workflow and press **Start**. Use the *Device simulator* bar to choose who or what is in
front of the camera (live person, low passive score, printed photo, screen, video replay, nobody, two
people) and how they respond (follow / ignore / wrong action / manual with keys **B S ← → ↑ ↓**). Tick
**Diagnostic mode** to see the audit trail and metrics. The demo reads `config/liveness.properties`.

## Layout

```
liveness-core/                       pure Java 11 library, zero dependencies (Desktop + Android)
  config/      LivenessConfig, LivenessPolicy, FailureAction
  device/      FaceCaptureDevice, FaceFrame, PixelFormat, DeviceInfo   (device adapter pattern)
  device/sbi/  SbiFaceStreamDevice, MjpegStreamReader                  (MOSIP SBI stream)
  engine/      LivenessEngine, FrameAnalysis, FaceObservation, PadResult (liveness/PAD interface)
  challenge/   ChallengeSelector (SecureRandom), blink/smile/head-pose detectors
  session/     LivenessSession state machine, UserMessage, FailureReason, LivenessResult
  service/     FaceLivenessService, LivenessHandle (threading, backpressure, device lifecycle)
  audit/       AuditEvent(Type), AuditSink, LivenessMetrics
  mock/        MockFaceDevice, SimulatedSubject, SimulatedLivenessEngine
liveness-desktop-demo/               reference face capture screen (Swing)
config/liveness.properties           sample per-workflow policy
docs/DESIGN.md                       technical design: architecture, flows, config, errors, security, ISO 30107
```

## Using it from a workflow

```java
FaceLivenessService service = new FaceLivenessService(
        LivenessConfig.load(Paths.get("liveness.properties")), engine, auditSink);

LivenessHandle h = service.start(LivenessWorkflow.OPERATOR_AUTHENTICATION, device, new LivenessListener() {
    public void onUpdate(LivenessUpdate u)   { /* show u.getMessage(), guidance, step, countdown, retry button */ }
    public void onComplete(LivenessResult r) {
        if (r.isAccepted()) match(r.getCapturedFrame());          // face match on the verified frame
        else handle(r.getRequiredAction());                     // BLOCK / supervisor override / alternate auth
    }
});
// h.retry() when the user presses Retry; h.cancel() to abort
```

See [docs/DESIGN.md](docs/DESIGN.md) for the full design.
