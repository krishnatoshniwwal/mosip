package io.mosip.registration.liveness.challenge;

import io.mosip.registration.liveness.challenge.ActionDetector.Progress;
import io.mosip.registration.liveness.engine.FaceObservation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionDetectorTest {

    private static final FaceBaseline BASELINE = new FaceBaseline(0.9f, 0.1f, 0f, 0f);

    @Test
    void blinkNeedsOpenClosedOpen() {
        ActionDetector blink = ActionDetectors.create(ChallengeType.BLINK, BASELINE, 20, 15);
        assertEquals(Progress.WAITING, blink.update(eyes(0.9f)));
        assertEquals(Progress.IN_PROGRESS, blink.update(eyes(0.1f)));
        assertEquals(Progress.IN_PROGRESS, blink.update(eyes(0.2f)));
        assertEquals(Progress.COMPLETED, blink.update(eyes(0.88f)));
    }

    @Test
    void eyesThatNeverCloseAreNotABlink() {
        ActionDetector blink = ActionDetectors.create(ChallengeType.BLINK, BASELINE, 20, 15);
        for (int i = 0; i < 30; i++) {
            assertEquals(Progress.WAITING, blink.update(eyes(0.85f + (i % 2) * 0.05f)));
        }
    }

    @Test
    void eyesClosedFromTheStartDoNotCount() {
        ActionDetector blink = ActionDetectors.create(ChallengeType.BLINK, BASELINE, 20, 15);
        assertEquals(Progress.WAITING, blink.update(eyes(0.1f)));
        assertEquals(Progress.WAITING, blink.update(eyes(0.9f)));
        assertEquals(Progress.IN_PROGRESS, blink.update(eyes(0.1f)));
        assertEquals(Progress.COMPLETED, blink.update(eyes(0.9f)));
    }

    @Test
    void smileMustRiseAboveBaselineAndHold() {
        ActionDetector smile = ActionDetectors.create(ChallengeType.SMILE, BASELINE, 20, 15);
        assertEquals(Progress.WAITING, smile.update(smile(0.15f)));
        assertEquals(Progress.IN_PROGRESS, smile.update(smile(0.4f)));
        assertEquals(Progress.IN_PROGRESS, smile.update(smile(0.8f)));
        assertEquals(Progress.COMPLETED, smile.update(smile(0.85f)));
    }

    @Test
    void habitualSmileRaisesTheBar() {
        ActionDetector smile = ActionDetectors.create(ChallengeType.SMILE, new FaceBaseline(0.9f, 0.5f, 0, 0), 20, 15);
        smile.update(smile(0.65f));
        assertEquals(Progress.IN_PROGRESS, smile.update(smile(0.7f)), "0.7 is only +0.2 over a 0.5 baseline");
    }

    @Test
    void headTurnIsMeasuredFromBaselineInTheRequestedDirection() {
        FaceBaseline turnedSlightly = new FaceBaseline(0.9f, 0.1f, 5f, 0f);
        ActionDetector left = ActionDetectors.create(ChallengeType.TURN_LEFT, turnedSlightly, 20, 15);
        assertEquals(Progress.WAITING, left.update(pose(8f, 0f)));
        assertEquals(Progress.IN_PROGRESS, left.update(pose(20f, 0f)), "only 15 degrees from baseline");
        assertEquals(Progress.IN_PROGRESS, left.update(pose(26f, 0f)));
        assertEquals(Progress.COMPLETED, left.update(pose(28f, 0f)));
    }

    @Test
    void oppositeTurnContradictsTheChallenge() {
        ActionDetector left = ActionDetectors.create(ChallengeType.TURN_LEFT, BASELINE, 20, 15);
        assertFalse(left.contradicts(pose(0f, 0f)));
        assertFalse(left.contradicts(pose(-10f, 0f)));
        assertTrue(left.contradicts(pose(-25f, 0f)));
        assertEquals(Progress.WAITING, left.update(pose(-25f, 0f)));
    }

    @Test
    void stillTurnedFromThePreviousChallengeIsNotAContradiction() {
        ActionDetector left = ActionDetectors.create(ChallengeType.TURN_LEFT, BASELINE, 20, 15);
        assertFalse(left.contradicts(pose(-30f, 0f)), "still turned right from the previous challenge");
        assertFalse(left.contradicts(pose(-18f, 0f)));
        assertFalse(left.contradicts(pose(-2f, 0f)));
        assertTrue(left.contradicts(pose(-25f, 0f)), "turning right again after returning is a wrong action");
    }

    @Test
    void lookUpAndDownUsePitch() {
        ActionDetector up = ActionDetectors.create(ChallengeType.LOOK_UP, BASELINE, 20, 15);
        up.update(pose(0f, 16f));
        assertEquals(Progress.COMPLETED, up.update(pose(0f, 18f)));
        ActionDetector down = ActionDetectors.create(ChallengeType.LOOK_DOWN, BASELINE, 20, 15);
        down.contradicts(pose(0f, 0f));
        assertTrue(down.contradicts(pose(0f, 16f)));
    }

    private static FaceObservation eyes(float openness) {
        return FaceObservation.builder().quality(80).eyes(openness).smile(0.1f).build();
    }

    private static FaceObservation smile(float value) {
        return FaceObservation.builder().quality(80).eyes(0.9f).smile(value).build();
    }

    private static FaceObservation pose(float yaw, float pitch) {
        return FaceObservation.builder().quality(80).eyes(0.9f).smile(0.1f).pose(yaw, pitch, 0f).build();
    }
}
