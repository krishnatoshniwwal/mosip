# Face Liveness and Presentation Attack Detection for the MOSIP Registration Client

**MOSIP Decode · Problem statement 04** · Team: Chiraag K V, Krishna Toshniwal, Samarth Patidar, Venkatachala Darshan Gurumurthy · Code: https://github.com/krishnatoshniwwal/mosip

## The problem

The Registration Client captures a resident's face, and authenticates operators and supervisors by face, but it
does not check that the face belongs to a live person in front of the device. A printed photo, a phone screen or
a replayed video can stand in for the person. The client needs liveness and Presentation Attack Detection (PAD)
that works with any compliant L0/L1 device, online or offline, without slowing honest users down.

## Our solution

A face liveness layer that sits in front of the client's existing capture. Nothing about the stored biometric
changes: the MDS-signed capture is still what the client saves or matches. Liveness decides whether that capture
may happen at all.

1. **Passive check first.** The engine scores the face stream with no action from the person. A confidently live
   face goes straight to capture.
2. **Random challenges when unsure.** If the score is below the threshold (or always, for supervisors), the client
   asks for 2–3 actions in random order: blink, smile, turn left or right, look up or down. The person never picks.
3. **Attack checks on every frame**, in every phase: a trained anti-spoofing model, screen and photo cues (moiré,
   flat lighting, bezel edges), a second face, the face jumping or leaving, the opposite of the requested action,
   and signs of injected video (virtual cameras, frozen or noise-free frames).
4. **Capture binding.** After liveness passes, the device capture runs while the stream stays open; the same
   person must stay in view, alone and bona fide, or the capture is discarded.

All of it runs on the station, so it behaves identically online and offline.

## How it maps to the brief

| Brief | What we built |
|---|---|
| L0/L1 device integration | Vendor-neutral device interface. MOSIP SBI discovery and `/stream` adapter, tested against MOSIP's own mock SBI (all conformance checks pass). Webcam and mock devices. |
| Passive liveness + PAD | YuNet face detection, MiniFASNet anti-spoofing (our Java output matches the original implementation exactly), replay and injection cues. |
| Active liveness | Six facial actions: blink (MediaPipe Iris eyelids), smile and look up/down (MediaPipe Face Mesh), turn left/right (YuNet landmarks). Each is judged against the person's own resting face; random order and count; a retry gets a new sequence. |
| Resident, operator, supervisor | One capture dialog for all three, with per-workflow policies: residents go to supervisor review after the last attempt, operators can fall back to password/OTP, supervisors always get challenges and a stricter threshold. Integration module for the Desktop client, with the exact controller changes documented. |
| Configuration | Every item in the brief, per workflow, validated at start-up (a typo cannot silently weaken the policy). |
| Errors and retries | All 13 error cases in the brief. Device faults never use up the person's attempts; repeated failures lock out per user. |
| UI/UX | Live mirrored preview with the enforced capture oval, one plain instruction at a time, countdown, guidance tips. Attacks only ever get a generic message. Nine languages, including right-to-left Arabic. |
| Online/offline | Same checks offline; signed model updates sync in the background when online. |
| Packet integrity | Every resident capture gets **signed liveness evidence** (a JWS signed with the station's TPM key) bound to the exact face bytes and registration ID. The server can tell a liveness-verified face from one that never went through liveness, and refuses forged, swapped or copied evidence. |
| ISO/IEC 30107 | Evaluation tool reporting APCER per attack type, BPCER and DET curves, plus a dataset recorder. |

## Security and privacy

- **No biometric data in logs or audit**: only decisions, scores, challenge names and reasons.
- **Least disclosure**: the person never learns which check caught an attack.
- **Explicit camera consent** before the camera turns on; refusal is audited and the camera stays off.
- **Signed model packages**: every model file is hash-checked against a signed manifest before use, with rollback protection.
- **Fail closed**: an engine or device fault never produces a pass.
- **Verifiable end to end**: the liveness decision travels in the packet as signed evidence, so a tampered client
  cannot skip liveness without the server noticing.

## Results

| Measure | Value |
|---|---|
| Automated tests | 273, across all modules |
| Engine time per frame (CPU, laptop, ~500x350 frames, all models) | about 50–55 ms |
| MOSIP mock SBI | discovered and streamed at about 22 fps; all conformance checks pass |
| Eye openness (Iris model) on public test photos | closed 0.03–0.13, open 0.25–0.36 |
| Smile score on public test photos | neutral 0.20–0.23, smiling 0.94–1.00 |
| **ISO/IEC 30107-3 evaluation** (APCER per attack type, BPCER) | _[to be filled from the team's recorded sessions]_ |

## What's next

- Publish the ISO/IEC 30107-3 evaluation on recorded sessions from the target devices, and calibrate the
  thresholds from it.
- Android: the core library already runs on Android's Java version, and the CameraX device and Flutter bridge
  are written and unit-tested (not yet run on a device), and the camera adapter turns frames upright. An Android
  inference engine running the same models, and the Flutter screen, remain.
- Merge the controller changes into `mosip/registration-client`, and validate on real L0/L1 hardware.

Design details: [DESIGN.md](DESIGN.md) · Screens: [UI_UX.md](UI_UX.md) · Technology choices:
[TECHNOLOGY_EVALUATION.md](TECHNOLOGY_EVALUATION.md) · Client integration: [REGCLIENT_INTEGRATION.md](REGCLIENT_INTEGRATION.md)
