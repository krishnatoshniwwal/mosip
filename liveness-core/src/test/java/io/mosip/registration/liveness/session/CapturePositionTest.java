package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.engine.AttackType;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.PadResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A face anywhere in the camera's view must not be captured: only a face inside the oval counts. */
class CapturePositionTest {

    private static final LivenessPolicy DEFAULT = LivenessPolicy.builder().build();

    @Test
    void faceBesideTheOvalIsNeverCaptured() {
        SimulationHarness h = new SimulationHarness(31);
        h.subject.setPlacement(0.85f, 0.48f, 1f);
        h.newSession(DEFAULT, 31);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.FAILED, result.getOutcome());
        assertNull(result.getCapturedFrame());
        assertTrue(result.getAttemptFailures().stream().allMatch(r -> r == FailureReason.FACE_NOT_POSITIONED),
                result.getAttemptFailures().toString());
        assertTrue(h.updates.stream().anyMatch(u -> u.getGuidance() == UserMessage.GUIDE_FACE_IN_AREA));
        assertTrue(h.updatesIn(LivenessState.PASSIVE_CHECK).stream().allMatch(u -> u.getProgress() == 0.0),
                "frames outside the oval must not advance the passive check");
        assertEquals(UserMessage.FAIL_POSITION, h.updatesIn(LivenessState.ATTEMPT_FAILED).get(0).getMessage());
    }

    @Test
    void faceBesideTheOvalGetsPositioningGuidanceEvenWhenTheModelCallsItAnAttack() {
        // Found on a phone: a live face at the frame edge, half lit, makes the anti-spoof model unreliable. A face
        // outside the oval never counts, so it must not be judged an attack either; the person is told to move.
        SimulationHarness h = new SimulationHarness(35);
        h.subject.setPlacement(0.85f, 0.48f, 1f);
        h.engineOverride = a -> new FrameAnalysis(a.getFaces(), a.getLivenessScore(),
                PadResult.attack(AttackType.SCREEN_DISPLAY, 20f));
        h.autoRetry = false;
        h.newSession(DEFAULT, 35);
        h.start();

        assertNull(h.run(30_000));

        assertTrue(h.audit.ofType(AuditEventType.PAD_ATTACK_DETECTED).isEmpty());
        assertTrue(h.updates.stream().anyMatch(u -> u.getGuidance() == UserMessage.GUIDE_FACE_IN_AREA));
        assertEquals(UserMessage.FAIL_POSITION, h.updatesIn(LivenessState.ATTEMPT_FAILED).get(0).getMessage());
    }

    @Test
    void anAttackInsideTheOvalIsStillRejected() {
        SimulationHarness h = new SimulationHarness(36);
        h.engineOverride = a -> new FrameAnalysis(a.getFaces(), a.getLivenessScore(),
                PadResult.attack(AttackType.SCREEN_DISPLAY, 20f));
        h.autoRetry = false;
        h.newSession(DEFAULT, 36);
        h.start();

        assertNull(h.run(30_000));

        assertEquals(1, h.audit.ofType(AuditEventType.PAD_ATTACK_DETECTED).size());
        assertEquals(UserMessage.FAIL_GENERIC, h.updatesIn(LivenessState.ATTEMPT_FAILED).get(0).getMessage());
    }

    @Test
    void strayAttackFramesOnARealFaceDoNotRejectIt() {
        // Calibrated on the team's recordings: real faces had up to 19% of their in-oval frames flagged (glare,
        // a turn of the head), attacks every frame. One frame in eight stays below 5 of any 20.
        SimulationHarness h = new SimulationHarness(37);
        int[] frame = {0};
        h.engineOverride = a -> ++frame[0] % 8 == 0
                ? new FrameAnalysis(a.getFaces(), a.getLivenessScore(), PadResult.attack(AttackType.UNKNOWN, 40f))
                : a;
        h.newSession(DEFAULT, 37);

        LivenessResult result = h.startAndRun();

        assertTrue(h.audit.ofType(AuditEventType.PAD_ATTACK_DETECTED).isEmpty());
        assertEquals(LivenessOutcome.PASSED, result.getOutcome(), result.getAttemptFailures().toString());
    }

    @Test
    void faceTooFarAwayIsAskedToMoveCloser() {
        SimulationHarness h = new SimulationHarness(32);
        h.subject.setPlacement(0.5f, 0.48f, 0.4f);
        h.autoRetry = false;
        h.newSession(DEFAULT, 32);
        h.start();

        assertNull(h.run(30_000));

        assertTrue(h.updates.stream().anyMatch(u -> u.getGuidance() == UserMessage.GUIDE_MOVE_CLOSER));
        assertEquals(LivenessState.ATTEMPT_FAILED, h.session.state());
        assertEquals(UserMessage.FAIL_POSITION, h.updatesIn(LivenessState.ATTEMPT_FAILED).get(0).getMessage());
    }

    @Test
    void faceTooCloseIsAskedToMoveBack() {
        SimulationHarness h = new SimulationHarness(33);
        h.subject.setPlacement(0.5f, 0.48f, 1.6f);
        h.autoRetry = false;
        h.newSession(DEFAULT, 33);
        h.start();
        h.run(30_000);

        assertTrue(h.updates.stream().anyMatch(u -> u.getGuidance() == UserMessage.GUIDE_MOVE_BACK));
        assertFalse(h.updatesIn(LivenessState.HOLD_STILL).stream().findAny().isPresent());
    }

    @Test
    void movingIntoTheOvalStartsTheCheckAndPasses() {
        SimulationHarness h = new SimulationHarness(34);
        h.subject.setPlacement(0.85f, 0.48f, 1f);
        h.newSession(DEFAULT, 34);
        h.start();
        h.run(2_000);
        assertNull(h.result);

        h.subject.setPlacement(0.5f, 0.48f, 1f);
        LivenessResult result = h.run(30_000);

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertEquals(1, result.getAttempts());
        float x = result.getCapturedFace().getCenterX();
        assertTrue(Math.abs(x - 0.5f) < 0.1f, "captured face must be the one inside the oval, x=" + x);
    }

    @Test
    void leavingTheOvalDuringAChallengeFailsTheAttempt() {
        SimulationHarness h = new SimulationHarness(35);
        h.subject.setLivenessLevel(58);
        h.autoRetry = false;
        h.newSession(DEFAULT, 35);
        float[] x = {0.5f};
        h.onUpdate = u -> {
            if (u.getState() == LivenessState.ACTIVE_CHALLENGE && x[0] < 0.9f) {
                // Drift out gradually: a sudden jump would trip the face-continuity check instead.
                x[0] += 0.02f;
                h.subject.setPlacement(x[0], 0.48f, 1f);
            }
        };
        h.start();
        h.run(30_000);

        assertEquals(LivenessState.ATTEMPT_FAILED, h.session.state());
        LivenessUpdate failed = h.updatesIn(LivenessState.ATTEMPT_FAILED).get(0);
        assertEquals(UserMessage.FAIL_POSITION, failed.getMessage());
        assertTrue(failed.getTips().contains(UserMessage.GUIDE_FACE_IN_AREA));
    }

    @Test
    void areaCheckCanBeDisabled() {
        SimulationHarness h = new SimulationHarness(36);
        h.subject.setPlacement(0.85f, 0.48f, 1f);
        h.newSession(DEFAULT.toBuilder().captureAreaEnabled(false).build(), 36);

        assertEquals(LivenessOutcome.PASSED, h.startAndRun().getOutcome());
    }
}
