package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.FaceObservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Final outcome of a liveness session, handed to the capture or authentication workflow. */
public final class LivenessResult {
    private final String sessionId;
    private final LivenessWorkflow workflow;
    private final LivenessOutcome outcome;
    private final FailureReason failureReason;
    private final FailureAction requiredAction;
    private final FaceFrame capturedFrame;
    private final FaceObservation capturedFace;
    private final float passiveScore;
    private final boolean activeVerificationPerformed;
    private final List<ChallengeType> challengesCompleted;
    private final int attempts;
    private final List<FailureReason> attemptFailures;
    private final String engine;
    private final long durationMs;

    LivenessResult(String sessionId, LivenessWorkflow workflow, LivenessOutcome outcome, FailureReason failureReason,
                   FailureAction requiredAction, FaceFrame capturedFrame, FaceObservation capturedFace,
                   float passiveScore, boolean activeVerificationPerformed, List<ChallengeType> challengesCompleted,
                   int attempts, List<FailureReason> attemptFailures, String engine, long durationMs) {
        this.sessionId = sessionId;
        this.workflow = workflow;
        this.outcome = outcome;
        this.failureReason = failureReason;
        this.requiredAction = requiredAction;
        this.capturedFrame = capturedFrame;
        this.capturedFace = capturedFace;
        this.passiveScore = passiveScore;
        this.activeVerificationPerformed = activeVerificationPerformed;
        this.challengesCompleted = Collections.unmodifiableList(new ArrayList<>(challengesCompleted));
        this.attempts = attempts;
        this.attemptFailures = Collections.unmodifiableList(new ArrayList<>(attemptFailures));
        this.engine = engine;
        this.durationMs = durationMs;
    }

    /** True when the workflow may use the captured face (liveness verified, or liveness disabled by policy). */
    public boolean isAccepted() {
        return outcome == LivenessOutcome.PASSED || outcome == LivenessOutcome.LIVENESS_DISABLED;
    }

    public String getSessionId() { return sessionId; }
    public LivenessWorkflow getWorkflow() { return workflow; }
    public LivenessOutcome getOutcome() { return outcome; }
    /** Null when accepted or cancelled. */
    public FailureReason getFailureReason() { return failureReason; }
    /** What the workflow must do after a final failure; null otherwise. */
    public FailureAction getRequiredAction() { return requiredAction; }
    /** Best frontal frame from the verified session, for the face biometric or matcher; null unless accepted. */
    public FaceFrame getCapturedFrame() { return capturedFrame; }
    public FaceObservation getCapturedFace() { return capturedFace; }
    /** Passive liveness score of the last attempt, NaN if not reached. */
    public float getPassiveScore() { return passiveScore; }
    public boolean isActiveVerificationPerformed() { return activeVerificationPerformed; }
    public List<ChallengeType> getChallengesCompleted() { return challengesCompleted; }
    public int getAttempts() { return attempts; }
    /** Reason for every failed attempt, in order, including device faults. */
    public List<FailureReason> getAttemptFailures() { return attemptFailures; }
    public String getEngine() { return engine; }
    public long getDurationMs() { return durationMs; }
}
