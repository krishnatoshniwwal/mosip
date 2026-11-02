# Test Data

Everything needed to try the solution. No accounts, passwords or servers are needed: liveness runs fully
offline on the station or phone, and the demo screens stand in for MOSIP's authentication services.

## Demo user IDs

The desktop Registration Client demo (`.\build.ps1 -Run -RegClient`) and the Android example app use these
pre-filled IDs. Login is by face only, so there is no password.

| Screen | ID | Notes |
|---|---|---|
| Operator login | `110011` | Failed attempts are counted per ID: 5 failures within 10 minutes lock it for 15 minutes. Use another ID (any number) to keep testing |
| Resident registration | Registration ID generated per run (`10001100010002` + 6 random digits) | Bound into the signed liveness evidence |
| Supervisor approval | `110012` | Stricter policy: passive threshold 80, always challenged, locked after 3 failures |

## Simulated people (no camera needed)

Run `.\build.ps1 -Run -Sim` (desktop) or pick **Simulated camera** in the Android example app. The *Device
simulator* bar sets who or what is in front of the camera:

| Presentation | Expected result |
|---|---|
| Live person | Passes, usually without a challenge |
| Live person, low passive score | Gets 2–3 random challenges (blink, smile, turn left or right; supervisors also look up or down) and passes by following them |
| Printed photo | Rejected as an attack |
| Screen display | Rejected as an attack |
| Video replay | May pass the passive check; fails the random challenges |
| Deepfake through a virtual camera | Rejected as an injection attack |
| Nobody / two people | "No face" / "only one person" guidance; never captured |
| Beside the oval / too far / too close | Positioning guidance; never captured |
| Wearing a mask / sunglasses | Asked to uncover; otherwise challenges that don't need the covered part |

How the simulated person responds can also be set: follows the instructions, ignores them, does the wrong action,
or manual control with the keys **B S ← → ↑ ↓** (blink, smile, turn left, turn right, look up, look down).
Tick **OBS Virtual Camera installed on station** to see a session refused before the camera opens.

## Configuration

[config/liveness.properties](../config/liveness.properties) holds every setting, with comments. The values used
in testing are the defaults in that file:

| Setting | Value |
|---|---|
| Passive threshold: resident and operator / supervisor | 70 / 80 |
| Attack rule | reject once 5 of the last 20 in-oval frames are flagged |
| Attempts per session: resident and operator / supervisor | 3 / 2 |
| When attempts run out: resident / operator / supervisor | supervisor override / alternate login / blocked |

## Biometric device

- **Laptop webcam:** `.\build.ps1 -Run`.
- **MOSIP mock SBI:** MOSIP's own mock device from `mosip-mock-services`. Setup is in the
  [README](../README.md#testing-with-the-mosip-mock-sbi). It streams a still photo, so the app correctly rejects
  it as an attack: it tests the integration, not a passing session.

## Models

Downloaded and checksum-verified by the build scripts on first run into `models/`: YuNet face detection,
MiniFASNet anti-spoofing (Apache 2.0), and MediaPipe Face Mesh and Iris. To test signed model packages, create a
throwaway key pair and sign the folder with the `models` tool (see the [README](../README.md#tools)); never use a
test key in production.

## Evaluation dataset

The ISO/IEC 30107-3 evaluation used 41 clips of 4 consenting team members, recorded with the `record` tool on a
laptop webcam (640x480, 15 fps, 5 seconds each):

| Kind | Clips |
|---|---|
| Real person (bona fide) | 29 |
| Photo of the person shown on a phone screen | 12 |

The clips are **not published**: they are biometric data of real people, kept only on the team's machine and
deleted after the evaluation. Participants are identified only by pseudonymous IDs (`S01`, `S02`, ...). To
reproduce the evaluation, record your own set with `.\build.ps1 -Tool "record dataset"` (it asks for consent
first) and run `.\build.ps1 -Tool "evaluate dataset"`.
