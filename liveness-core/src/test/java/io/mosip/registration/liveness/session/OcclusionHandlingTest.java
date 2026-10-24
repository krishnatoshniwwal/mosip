package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.engine.FaceRegion;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcclusionHandlingTest {

    private static final LivenessPolicy DEFAULT = LivenessPolicy.builder().build();

    @Test
    void maskedFaceIsAskedToUncoverAndPassesPassivelyOnceUncovered() {
        SimulationHarness h = new SimulationHarness(41);
        h.subject.setOcclusion(EnumSet.of(FaceRegion.LOWER_FACE));
        h.newSession(DEFAULT, 41);
        h.start();
        h.run(1_500);
        assertTrue(h.updates.stream().anyMatch(u -> u.getGuidance() == UserMessage.GUIDE_REMOVE_FACE_COVERING));

        h.subject.setOcclusion(Collections.emptySet());
        LivenessResult result = h.run(30_000);

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertFalse(result.isActiveVerificationPerformed(), "uncovered within the grace period: no fallback needed");
        assertTrue(h.audit.ofType(AuditEventType.OCCLUSION_FALLBACK).isEmpty());
    }

    @Test
    void sunglassesAreAskedToBeRemoved() {
        SimulationHarness h = new SimulationHarness(42);
        h.subject.setOcclusion(EnumSet.of(FaceRegion.EYES));
        h.newSession(DEFAULT, 42);
        h.start();
        h.run(1_500);

        assertTrue(h.updates.stream().anyMatch(u -> u.getGuidance() == UserMessage.GUIDE_REMOVE_SUNGLASSES));
    }

    @Test
    void persistentMaskFallsBackToChallengesThatDoNotNeedTheMouth() {
        SimulationHarness h = new SimulationHarness(43);
        h.subject.setOcclusion(EnumSet.of(FaceRegion.LOWER_FACE));
        LivenessPolicy policy = DEFAULT.toBuilder().occlusionAllowCapture(true)
                .challengeTypes(Arrays.asList(ChallengeType.SMILE, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT,
                        ChallengeType.BLINK)).build();
        h.newSession(policy, 43);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertTrue(result.isActiveVerificationPerformed(), "a covered face must always prove liveness actively");
        assertFalse(result.getChallengesCompleted().contains(ChallengeType.SMILE), result.getChallengesCompleted().toString());
        assertEquals("[LOWER_FACE]", h.audit.ofType(AuditEventType.OCCLUSION_FALLBACK).get(0).getDetails().get("regions"));
    }

    @Test
    void persistentSunglassesNeverGetABlinkChallenge() {
        SimulationHarness h = new SimulationHarness(44);
        h.subject.setOcclusion(EnumSet.of(FaceRegion.EYES));
        LivenessPolicy policy = DEFAULT.toBuilder().occlusionAllowCapture(true).minChallenges(3).maxChallenges(3)
                .challengeTypes(Arrays.asList(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT,
                        ChallengeType.TURN_RIGHT)).build();
        h.newSession(policy, 44);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertFalse(result.getChallengesCompleted().contains(ChallengeType.BLINK));
        assertEquals(3, result.getChallengesCompleted().size());
    }

    @Test
    void residentFaceIsNotCapturedWhileCovered() {
        SimulationHarness h = new SimulationHarness(45);
        h.subject.setOcclusion(EnumSet.of(FaceRegion.LOWER_FACE));
        h.newSession(DEFAULT, 45);   // occlusion.allowCapture=false by default

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.FAILED, result.getOutcome());
        assertNull(result.getCapturedFrame());
        assertTrue(result.getAttemptFailures().stream().allMatch(r -> r == FailureReason.FACE_OCCLUDED),
                result.getAttemptFailures().toString());
        assertEquals(UserMessage.FAIL_OCCLUDED, h.updatesIn(LivenessState.ATTEMPT_FAILED).get(0).getMessage());
        assertTrue(h.updatesIn(LivenessState.HOLD_STILL).stream()
                .anyMatch(u -> u.getGuidance() == UserMessage.GUIDE_REMOVE_FACE_COVERING));
    }

    @Test
    void maskMissedOnSomeFramesIsStillNotCaptured() {
        for (int period : new int[] {2, 3, 5}) {
            SimulationHarness h = new SimulationHarness(48 + period);
            int[] frame = {0};
            // Real detectors flicker: the mask is seen on only one frame in every `period`.
            h.onUpdate = u -> h.subject.setOcclusion(++frame[0] % period == 0
                    ? EnumSet.of(FaceRegion.LOWER_FACE) : Collections.emptySet());
            h.newSession(DEFAULT, 48 + period);

            LivenessResult result = h.startAndRun();

            assertEquals(LivenessOutcome.FAILED, result.getOutcome(), "detected 1 in " + period + " frames");
            assertNull(result.getCapturedFrame());
        }
    }

    @Test
    void noUsableChallengeLeftFailsAsOccluded() {
        SimulationHarness h = new SimulationHarness(46);
        h.subject.setOcclusion(EnumSet.of(FaceRegion.LOWER_FACE));
        h.autoRetry = false;
        h.newSession(DEFAULT.toBuilder().occlusionAllowCapture(true).minChallenges(1).maxChallenges(1)
                .challengeTypes(Collections.singletonList(ChallengeType.SMILE)).build(), 46);
        h.start();
        h.run(30_000);

        assertEquals(LivenessState.ATTEMPT_FAILED, h.session.state());
        assertEquals(UserMessage.FAIL_OCCLUDED, h.updatesIn(LivenessState.ATTEMPT_FAILED).get(0).getMessage());
    }

    @Test
    void occlusionHandlingCanBeDisabled() {
        SimulationHarness h = new SimulationHarness(47);
        h.subject.setOcclusion(EnumSet.of(FaceRegion.LOWER_FACE));
        h.newSession(DEFAULT.toBuilder().occlusionEnabled(false).build(), 47);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertFalse(result.isActiveVerificationPerformed());
    }
}
