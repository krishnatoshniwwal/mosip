package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.mock.SimulatedSubject.Presentation;
import io.mosip.registration.liveness.mock.SimulatedSubject.ResponseMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivenessSessionTest {

    private static final LivenessPolicy DEFAULT = LivenessPolicy.builder().build();

    @Test
    void confidentLivePersonPassesOnPassiveLivenessAlone() {
        SimulationHarness h = new SimulationHarness(1);
        h.newSession(DEFAULT, 1);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertTrue(result.isAccepted());
        assertFalse(result.isActiveVerificationPerformed());
        assertTrue(result.getPassiveScore() >= DEFAULT.getPassiveThreshold());
        assertNotNull(result.getCapturedFrame());
        assertTrue(Math.abs(result.getCapturedFace().getYaw()) <= LivenessSession.FRONTAL_MAX_ANGLE);
        assertEquals(1, result.getAttempts());
        assertEquals(UserMessage.CHECKING_LIVENESS, h.updates.get(0).getMessage());
        assertEquals(UserMessage.SUCCESS, h.updates.get(h.updates.size() - 1).getMessage());
    }

    @Test
    void lowPassiveScoreTriggersRandomChallengesThatALivePersonCompletes() {
        SimulationHarness h = new SimulationHarness(2);
        h.subject.setLivenessLevel(58);
        h.newSession(DEFAULT, 2);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertTrue(result.isActiveVerificationPerformed());
        List<ChallengeType> done = result.getChallengesCompleted();
        assertTrue(done.size() >= DEFAULT.getMinChallenges() && done.size() <= DEFAULT.getMaxChallenges(), done.toString());
        assertEquals(done.size(), new HashSet<>(done).size(), "challenges must not repeat");
        assertFalse(h.updatesIn(LivenessState.ACTIVE_CHALLENGE).isEmpty());
        assertFalse(h.updatesIn(LivenessState.CHALLENGE_FEEDBACK).isEmpty());
        assertEquals(done.size(), h.audit.ofType(AuditEventType.CHALLENGE_PASSED).size());
    }

    @Test
    void activeAlwaysIssuesChallengesEvenWhenPassiveIsConfident() {
        SimulationHarness h = new SimulationHarness(3);
        h.newSession(DEFAULT.toBuilder().activeAlways(true).build(), 3);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertTrue(result.isActiveVerificationPerformed());
    }

    @Test
    void unresponsiveSubjectTimesOutAndExhaustsRetries() {
        SimulationHarness h = new SimulationHarness(4);
        h.subject.setLivenessLevel(58);
        h.subject.setResponseMode(ResponseMode.IGNORE_PROMPTS);
        LivenessPolicy policy = DEFAULT.toBuilder().retryExhaustedAction(FailureAction.REQUIRE_SUPERVISOR_OVERRIDE).build();
        h.newSession(policy, 4);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.FAILED, result.getOutcome());
        assertEquals(FailureReason.MAX_RETRIES_EXCEEDED, result.getFailureReason());
        assertEquals(FailureAction.REQUIRE_SUPERVISOR_OVERRIDE, result.getRequiredAction());
        assertEquals(policy.getMaxRetries() + 1, result.getAttemptFailures().size());
        assertTrue(result.getAttemptFailures().stream().allMatch(r -> r == FailureReason.CHALLENGE_TIMEOUT));
        assertNull(result.getCapturedFrame());
        LivenessUpdate last = h.updates.get(h.updates.size() - 1);
        assertEquals(UserMessage.FINAL_SUPERVISOR_OVERRIDE, last.getMessage());
        LivenessUpdate retryPrompt = h.updatesIn(LivenessState.ATTEMPT_FAILED).get(0);
        assertTrue(retryPrompt.isRetryAvailable());
        assertTrue(retryPrompt.getTips().contains(UserMessage.GUIDE_FOLLOW_ACTION));
    }

    @Test
    void printedPhotoIsRejectedByPadWithAGenericMessage() {
        SimulationHarness h = new SimulationHarness(5);
        h.subject.setPresentation(Presentation.PRINTED_PHOTO);
        h.newSession(DEFAULT, 5);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.FAILED, result.getOutcome());
        assertTrue(result.getAttemptFailures().stream().allMatch(r -> r == FailureReason.PAD_ATTACK_DETECTED));
        for (LivenessUpdate u : h.updatesIn(LivenessState.ATTEMPT_FAILED)) {
            assertEquals(UserMessage.FAIL_GENERIC, u.getMessage(), "PAD details must not reach the user");
            assertFalse(u.getMessage().defaultText().toLowerCase().contains("photo"));
        }
        List<AuditEvent> pad = h.audit.ofType(AuditEventType.PAD_ATTACK_DETECTED);
        assertFalse(pad.isEmpty());
        assertEquals("PRINTED_PHOTO", pad.get(0).getDetails().get("attackType"));
    }

    @Test
    void videoReplayThatEvadesPassivePadIsStoppedByActiveChallenges() {
        SimulationHarness h = new SimulationHarness(6);
        h.subject.setPresentation(Presentation.VIDEO_REPLAY);
        h.newSession(DEFAULT, 6);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.FAILED, result.getOutcome());
        assertFalse(result.getAttemptFailures().contains(FailureReason.PAD_ATTACK_DETECTED));
        assertTrue(result.getAttemptFailures().stream().allMatch(
                r -> r == FailureReason.CHALLENGE_TIMEOUT || r == FailureReason.CHALLENGE_FAILED),
                result.getAttemptFailures().toString());
    }

    @Test
    void oppositeHeadTurnFailsTheChallengeImmediately() {
        SimulationHarness h = new SimulationHarness(7);
        h.subject.setLivenessLevel(58);
        h.subject.setResponseMode(ResponseMode.WRONG_ACTION);
        h.autoRetry = false;
        h.newSession(DEFAULT.toBuilder()
                .challengeTypes(Arrays.asList(ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT))
                .minChallenges(1).maxChallenges(1).build(), 7);
        h.start();

        h.run(60_000);

        assertEquals(LivenessState.ATTEMPT_FAILED, h.session.state());
        assertEquals(List.of(FailureReason.CHALLENGE_FAILED), failures(h));
    }

    @Test
    void secondFaceDuringActiveVerificationFailsTheAttempt() {
        SimulationHarness h = new SimulationHarness(8);
        h.subject.setLivenessLevel(58);
        h.subject.setResponseMode(ResponseMode.IGNORE_PROMPTS);
        h.autoRetry = false;
        h.onUpdate = u -> {
            if (u.getState() == LivenessState.ACTIVE_CHALLENGE) {
                h.subject.setPresentation(Presentation.TWO_FACES);
            }
        };
        h.newSession(DEFAULT, 8);
        h.start();

        h.run(60_000);

        assertEquals(List.of(FailureReason.MULTIPLE_FACES), failures(h));
    }

    @Test
    void noFaceTimesOutWithFaceNotDetected() {
        SimulationHarness h = new SimulationHarness(9);
        h.subject.setPresentation(Presentation.NO_FACE);
        h.autoRetry = false;
        h.newSession(DEFAULT, 9);
        h.start();

        h.run(60_000);

        assertEquals(List.of(FailureReason.FACE_NOT_DETECTED), failures(h));
        LivenessUpdate during = h.updatesIn(LivenessState.PASSIVE_CHECK).get(1);
        assertEquals(UserMessage.GUIDE_FACE_IN_AREA, during.getGuidance());
    }

    @Test
    void poorQualityTimesOutWithQualityGuidance() {
        SimulationHarness h = new SimulationHarness(10);
        h.subject.setQuality(20);
        h.autoRetry = false;
        h.newSession(DEFAULT, 10);
        h.start();

        h.run(60_000);

        assertEquals(List.of(FailureReason.POOR_FACE_QUALITY), failures(h));
    }

    @Test
    void deviceFaultsDoNotConsumeTheRetryBudget() {
        SimulationHarness h = new SimulationHarness(11);
        LivenessPolicy policy = DEFAULT.toBuilder().maxRetries(0).build();
        h.newSession(policy, 11);
        h.start();
        for (int i = 0; i < 4; i++) {
            h.session.onSystemError(FailureReason.DEVICE_DISCONNECTED, "unplugged", h.clock.get());
            assertEquals(LivenessState.ATTEMPT_FAILED, h.session.state());
            assertEquals(UserMessage.FAIL_DEVICE, h.updates.get(h.updates.size() - 1).getMessage());
            assertTrue(h.session.retry(h.clock.get()));
        }

        LivenessResult result = h.run(60_000);

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertEquals(5, result.getAttempts());
        assertEquals(4, result.getAttemptFailures().size());
    }

    @Test
    void challengeTypesUnsupportedByEngineEndTheSessionAsConfigurationError() {
        SimulationHarness h = new SimulationHarness(12);
        h.subject.setLivenessLevel(58);
        EngineInfo blinkOnly = new EngineInfo("blink-only", "1", "1", EnumSet.of(ChallengeType.BLINK), true);
        h.newSession(DEFAULT.toBuilder().challengeTypes(Arrays.asList(ChallengeType.SMILE, ChallengeType.TURN_LEFT))
                .minChallenges(1).maxChallenges(2).build(), 12, blinkOnly);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.FAILED, result.getOutcome());
        assertEquals(FailureReason.CONFIGURATION_ERROR, result.getFailureReason());
        assertEquals(FailureAction.BLOCK, result.getRequiredAction());
        assertEquals(1, result.getAttempts());
    }

    @Test
    void passiveOnlyPolicyFailsLowScoresWithoutChallenges() {
        SimulationHarness h = new SimulationHarness(13);
        h.subject.setLivenessLevel(58);
        h.autoRetry = false;
        h.newSession(DEFAULT.toBuilder().activeEnabled(false).build(), 13);
        h.start();

        h.run(60_000);

        assertEquals(List.of(FailureReason.LIVENESS_BELOW_THRESHOLD), failures(h));
        assertTrue(h.updatesIn(LivenessState.ACTIVE_CHALLENGE).isEmpty());
    }

    @Test
    void disabledLivenessCapturesWithoutLivenessOrPadChecks() {
        SimulationHarness h = new SimulationHarness(14);
        h.subject.setPresentation(Presentation.PRINTED_PHOTO);
        h.newSession(DEFAULT.toBuilder().enabled(false).build(), 14);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.LIVENESS_DISABLED, result.getOutcome());
        assertTrue(result.isAccepted());
        assertNotNull(result.getCapturedFrame());
        assertTrue(h.audit.ofType(AuditEventType.PAD_ATTACK_DETECTED).isEmpty());
    }

    @Test
    void cancelEndsTheSessionOnce() {
        SimulationHarness h = new SimulationHarness(15);
        h.newSession(DEFAULT, 15);
        h.start();

        h.session.cancel(h.clock.get());
        h.session.cancel(h.clock.get());

        assertEquals(LivenessOutcome.CANCELLED, h.result.getOutcome());
        assertEquals(1, h.audit.ofType(AuditEventType.SESSION_CANCELLED).size());
        assertFalse(h.session.retry(h.clock.get()));
    }

    @Test
    void retryUsesADifferentChallengeSequenceWhenPossible() {
        SimulationHarness h = new SimulationHarness(16);
        h.subject.setLivenessLevel(58);
        h.subject.setResponseMode(ResponseMode.IGNORE_PROMPTS);
        h.newSession(DEFAULT.toBuilder().challengeTimeoutMs(1000).build(), 16);

        h.startAndRun();

        List<AuditEvent> issued = h.audit.ofType(AuditEventType.CHALLENGE_ISSUED);
        assertEquals(DEFAULT.getMaxRetries() + 1, issued.size(), "each attempt fails on its first challenge");
        assertTrue(issued.stream().map(e -> e.getDetails().get("challenge")).distinct().count() > 1,
                "challenge order should vary between attempts");
    }

    @Test
    void auditRecordsCarryNoBiometricPayload() {
        SimulationHarness h = new SimulationHarness(17);
        h.subject.setLivenessLevel(58);
        h.newSession(DEFAULT, 17);

        h.startAndRun();

        for (AuditEvent event : h.audit.events()) {
            for (String value : event.getDetails().values()) {
                assertTrue(value.length() < 200, "unexpectedly large audit value in " + event);
            }
        }
        assertEquals(1, h.audit.ofType(AuditEventType.LIVENESS_PASSED).size());
    }

    /** Reasons of all failed attempts so far, from the audit trail. */
    private static List<FailureReason> failures(SimulationHarness h) {
        List<FailureReason> reasons = new ArrayList<>();
        for (AuditEvent event : h.audit.ofType(AuditEventType.ATTEMPT_FAILED)) {
            reasons.add(FailureReason.valueOf(event.getDetails().get("reason")));
        }
        return reasons;
    }
}
