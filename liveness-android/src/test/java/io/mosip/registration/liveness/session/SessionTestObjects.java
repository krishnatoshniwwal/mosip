package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.FaceObservation;

import java.util.List;

/**
 * Test-only access to the package-private constructors of {@link LivenessUpdate} and {@link LivenessResult}
 * (only the session creates them in production), for tests in other modules that serialise these objects.
 */
public final class SessionTestObjects {

    private SessionTestObjects() {
    }

    public static LivenessUpdate update(String sessionId, LivenessWorkflow workflow, LivenessState state,
                                        UserMessage message, UserMessage guidance, List<UserMessage> tips,
                                        ChallengeType challenge, int challengeNumber, int challengeCount,
                                        double progress, long remainingMs, int attemptSequence, int attempt,
                                        int maxAttempts, boolean retryAvailable) {
        return new LivenessUpdate(sessionId, workflow, state, message, guidance, tips, challenge, challengeNumber,
                challengeCount, progress, remainingMs, attemptSequence, attempt, maxAttempts, retryAvailable);
    }

    public static LivenessResult result(String sessionId, LivenessWorkflow workflow, LivenessOutcome outcome,
                                        FailureReason failureReason, FailureAction requiredAction,
                                        FaceFrame capturedFrame, FaceObservation capturedFace, float passiveScore,
                                        boolean activeVerificationPerformed, List<ChallengeType> challengesCompleted,
                                        int attempts, List<FailureReason> attemptFailures, String engine,
                                        long durationMs, long lockedUntil) {
        return new LivenessResult(sessionId, workflow, outcome, failureReason, requiredAction, capturedFrame,
                capturedFace, passiveScore, activeVerificationPerformed, challengesCompleted, attempts,
                attemptFailures, engine, durationMs, lockedUntil);
    }
}
