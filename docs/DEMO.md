# Demo script and evaluation day plan

## Live demo (about 4 minutes)

Run `.\build.ps1` once beforehand so models and libraries are downloaded and the tests are green. Keep a
printed photo of a team member, a phone with that person's photo and a short video of them, and OBS Studio
installed (Virtual Camera off). Have the example app on an Android phone (see `liveness-flutter/README.md`),
charged, with the camera permission already granted.

| # | Show | Command / action | What the judges see |
|---|---|---|---|
| 1 | Honest user passes fast | `.\build.ps1 -Run`, resident workflow, Start | Oval, passive score above threshold, captured with no challenge |
| 2 | Printed photo | Hold the print in the oval | Rejected with a generic message; diagnostic mode shows which check fired |
| 3 | Phone screen | Show the photo on the phone | Rejected (moiré / bezel / MiniFASNet) |
| 4 | Video replay | Play the video on the phone | Passive may pass; random challenges (blink, turn) catch it |
| 5 | Injection attack | Start OBS Virtual Camera, Start again | Session refused before the camera opens (CEN/TS 18099) |
| 6 | Real client flow | `.\build.ps1 -Run -RegClient` | Operator login → resident capture → supervisor auth, each with its own policy |
| 7 | Inclusion | Switch language to Arabic, then Hindi | RTL layout, translated prompts |
| 8 | Offline | Toggle the online/offline badge | Same checks offline; signed model sync when online |
| 9 | Same on Android | On the phone: Camera mode, **Login with face**, then hold up the printed photo | The same prompts, oval and messages as desktop, the same engine and models on the phone; the photo is rejected. Open *Diagnostics* for the on-device time per frame |

If the venue camera or lighting misbehaves, switch to `.\build.ps1 -Run -Sim` and drive the same attacks
from the *Device simulator* bar. Record the full demo as a video in advance as a backup.

## Evaluation day (fills the Results table in SUBMISSION.md)

1. Consent form signed by each participant; pseudonymous IDs `S01`, `S02`, …
2. `.\build.ps1 -Tool "record dataset"`. Per participant, at least: 3 live clips (near, far, glasses or different
   lighting), 2 printed photo, 2 phone screen photo, 2 video replay. Target 10+ participants.
3. `.\build.ps1 -Tool "evaluate dataset"` → `pad-evaluation/report.md`.
4. Copy the operating points (engine verdict, configured threshold, APCER per species, BPCER) into the Results
   table of `SUBMISSION.md`, and set `passive.threshold` from the report's Calibration section.
5. Delete `dataset/` once the numbers are in. It is biometric data and is git-ignored, so never commit it.
