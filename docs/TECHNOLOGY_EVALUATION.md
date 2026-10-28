# Face Liveness: Technology Evaluation

Why the engine uses the models and runtimes it does. "Measured" means measured on this project (the probes and
tests behind each number are named). Statements about alternatives we did not run are marked as such.

## Requirements every option had to meet

These come from the problem statement and the hackathon rules.

| Requirement | Why |
|---|---|
| **Runs offline, on the station** | Liveness must not depend on network availability. |
| **OSI-approved licence, redistributable** | Hackathon rule, and the code may be contributed to MOSIP. Commercial SDKs and cloud APIs are excluded on this ground alone. |
| **Usable from Java on Windows and Linux, and on Android** | The Desktop Registration Client is Java/JavaFX; the Android client is Java/Flutter. |
| **Vendor neutral** | Must work with any compliant L0/L1 device and with a plain camera. |
| **Small and fast on a CPU** | Registration stations and tablets have no GPU guarantee; the analysis must keep up with the preview. |
| **Measures what the challenges need** | Face position, head pose, eye openness and smile, plus a passive bona fide score. |

## Summary of the choice

| Job | Chosen | Licence | Size | Runtime |
|---|---|---|---|---|
| Face detection + 5 landmarks | **YuNet** (OpenCV model zoo, 2023mar) | MIT | 0.23 MB | OpenCV `FaceDetectorYN` |
| Passive PAD (bona fide score) | **MiniFASNet V2 + V1SE ensemble** (Silent-Face-Anti-Spoofing) | Apache-2.0 | 2 × 1.7 MB | ONNX Runtime (converted by us) |
| Smile, pitch, eye positions | **MediaPipe Face Mesh** (468 landmarks) | Apache-2.0 | 1.2 MB | OpenCV DNN, TFLite file unmodified |
| Eye openness (blink) | **MediaPipe Iris** (eyelid contour) | Apache-2.0 | 2.6 MB | OpenCV DNN, TFLite file unmodified |
| Head turn (yaw) | Geometry on the YuNet landmarks | ours | none | Java |
| Replay, occlusion, injection cues | Image and stream analysis | ours | none | OpenCV / Java |

All of it runs on a CPU; GPU (OpenCL) is optional and is only used when it proves faster and gives the same
output. **Measured** on the development laptop, for frames of about 490x360 to 550x350: about 35 ms per frame
before the face mesh and iris models were added (`interop` benchmark on MOSIP mock SBI frames, median), and
about 50–55 ms with every model (scratch probes).

## 1. Face detection

| Option | Landmarks | Licence | Notes |
|---|---|---|---|
| **YuNet** (chosen) | 5 (eyes, nose, mouth corners) | MIT | Built into OpenCV 4.8+ as `FaceDetectorYN`, so no extra runtime. 0.23 MB. |
| Haar cascade (OpenCV) | none | Intel BSD-style | Was the first fallback in this project. **Measured:** the XML is not even shipped in the OpenCV jar we use, so detection silently returned no face. Known to be weak on pose and lighting. |
| OpenCV Res10 SSD (Caffe) | none | not clearly stated | Larger (~10 MB) and gives no landmarks, so head pose would need a second model. Not run. |
| MediaPipe BlazeFace | 6 keypoints | Apache-2.0 | A good alternative; TFLite. Not run, because YuNet already gives the landmarks we need inside OpenCV. |
| RetinaFace, MTCNN | 5 | MIT (common ports) | Heavier, typically slower on CPU. Not run. |

**Measured:** YuNet found the face in 11 of 12 test photos of varied quality, and in every frame of the engine
benchmark on MOSIP mock SBI frames (50 of 50).

## 2. Passive presentation attack detection

| Option | Licence | Notes |
|---|---|---|
| **MiniFASNet V2 + V1SE** (chosen) | Apache-2.0 | Small CNNs trained to separate real faces from photo and screen attacks; each looks at the face with surrounding context (crop enlarged 2.7x and 4x), so a phone bezel or paper edge is visible. |
| Hand-written cues (texture, frequency, colour) | ours | The team's first version. **Measured:** scored a genuine face photo 64/100, below the 70 threshold, so every honest user would have been challenged every time. Kept only as a fallback when the models are missing. |
| Deeper research models (CDCN and similar) | varies | Better reported accuracy, but larger, and published weights often come with research-only terms. Not run. |
| Commercial SDKs (FaceTec, iProov, etc.) | proprietary | Excluded: not OSI-licensed, and several need a cloud service. |
| Depth or IR hardware | n/a | Strong against flat attacks, but ties the solution to specific devices. The engine interface can take an L1 device's own liveness result, so such hardware can be added without changing the session. |

**Measured:**

- Our Java pipeline reproduces the original Python implementation exactly: the same score, 0.9262222, on the
  same image and face box, and the enlarged crop boxes match pixel for pixel (`MiniFasNetEnsembleTest`).
- A genuine face photo scored 92.6. MOSIP's mock SBI stream (a still photo with boxes and dots drawn on it)
  scored 11.3–16.2 and was rejected as an attack in every run.
- Both models together take about 14 ms per frame.

The model only says "fake", not which kind. The replay cues (`ReplayAttackDetector`: moiré, flat lighting,
bezel edges) add evidence and can only lower the score, never raise it. Their thresholds are first estimates and
still need calibration on recorded attacks.

## 3. Facial measurements for the challenges

| Option | Licence | Notes |
|---|---|---|
| **MediaPipe Face Mesh** (chosen for smile and pitch) | Apache-2.0 | 468 3D landmarks. Runs unmodified through OpenCV's TFLite reader, so no model conversion (and no conversion errors). |
| **MediaPipe Iris** (chosen for eye openness) | Apache-2.0 | Detailed eyelid contour per eye; runs the same way. |
| MediaPipe Face Mesh with attention | Apache-2.0 | Has refined eyes and lips in one model, but relies on MediaPipe's own custom operations, which a plain TFLite or OpenCV reader does not provide. Not run. |
| dlib 68-point shape predictor | Boost (code) | The pretrained model is built on the iBUG 300-W data, whose licence excludes commercial use. No Java binding. Not used. |
| OpenCV Facemark LBF | OpenCV contrib | The `face` contrib module is not in the OpenCV build we use. |
| Bounding-box position as head pose | ours | The team's first version. **Measured:** sliding a photo across the frame looked like a head turn (now a unit test that must fail to fool the estimator). |

**Measured (scratch probes on public photos):**

| Signal | Closed eyes / neutral | Open eyes / smiling | Separates? |
|---|---|---|---|
| Face Mesh eye aspect ratio | 0.18–0.30 (3 closed-eye photos) | 0.17–0.31 (2 open-eye photos) | **No.** The mesh draws a standard eye shape, so it cannot detect a blink. |
| **Iris eyelid aspect ratio** | 0.03–0.13 (2 closed-eye photos) | 0.25–0.36 (5 open-eye photos) | **Yes** |
| **Smile score** (mouth width + corner lift) | 0.20–0.23 (3 neutral faces) | 0.94–1.00 (4 smiling faces) | **Yes** |

The Face Mesh row is why blink uses the Iris model: the obvious choice would have shipped a blink challenge that
never detects a blink. On the third closed-eye photo (deep leaf shadows) the mesh misplaced the eyes, so the
iris model never saw them; that photo is left out of the iris row. Webcam faces are frontal and far easier. All challenges are judged against each person's own resting baseline, so the
mappings only need the right direction and range.

## 4. Inference runtime

| Option | Notes |
|---|---|
| **OpenCV DNN** (chosen for YuNet, Face Mesh, Iris) | Already needed for image handling; reads ONNX and TFLite; Apache-2.0. OpenCL GPU optional. |
| **ONNX Runtime** (chosen for MiniFASNet) | MIT; Java bindings on desktop and an Android build. |
| TensorFlow Lite Java | The natural runtime on Android (the MediaPipe models are TFLite already). On desktop it is not a maintained Java option, so desktop uses OpenCV for the same files. |
| A cloud inference service | Excluded: liveness must work offline. |

Android reuses the same model files: the MediaPipe models run natively in TFLite, YuNet through OpenCV for
Android or a TFLite detector, and MiniFASNet through ONNX Runtime for Android.

## 5. Liveness approach

| Approach | Decision |
|---|---|
| Passive only | Fast and effortless, but a high-quality replay can pass it. |
| Active only | Robust against replays, but makes every honest user perform actions. |
| **Hybrid** (chosen, as the problem statement asks) | Passive first; random challenges only when the passive score is below the threshold, or always for supervisors. |
| Screen-flash colour challenge | Strong against replays, but needs control of screen brightness and calibration per station. Deferred. |
| rPPG (pulse from skin colour) | Needs about 10 s of steady video and is fragile with webcam compression and lighting. Deferred. |

**Measured:** in the simulation tests, a video replay that evades passive PAD is stopped by the challenges,
because it cannot follow a random sequence (`LivenessSessionTest`). Head-turn challenges also resist flat
photos: the estimator uses the nose's position relative to the eyes, so moving a photo across the frame does
not count as a turn (`HeadPoseEstimatorTest`). Rotating a flat photo should not count either, because on a flat
surface the nose stays in the same place relative to the eyes; this is not yet tested with a printed photo.

## 6. What is still unmeasured

- **APCER and BPCER on recorded live and attack sessions** from the target devices. The evaluation tool exists
  (`evaluate`); the dataset is being recorded.
- Thresholds for the replay, occlusion and injection cues, and the pitch angle for look up/down.
- A passing session on real L0/L1 SBI hardware (tested so far against MOSIP's mock SBI only).
- Performance on low-end Android devices.
