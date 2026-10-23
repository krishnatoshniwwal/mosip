package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.challenge.ChallengeType;

import java.util.List;

/** Snapshot of the session for the UI; emitted on every processed frame and state change. */
public final class LivenessUpdate {
    private final String sessionId;
    private final LivenessWorkflow workflow;
    private final LivenessState state;
    private final UserMessage message;
    private final UserMessage guidance;
    private final List<UserMessage> tips;
    private final ChallengeType currentChallenge;
    private final int challengeNumber;
    private final int challengeCount;
    private final double progress;
    private final long challengeRemainingMs;
    private final int attemptSequence;
    private final int attempt;
    private final int maxAttempts;
    private final boolean retryAvailable;

    LivenessUpdate(String sessionId, LivenessWorkflow workflow, LivenessState state, UserMessage message,
                   UserMessage guidance, List<UserMessage> tips, ChallengeType currentChallenge, int challengeNumber,
                   int challengeCount, double progress, long challengeRemainingMs, int attemptSequence, int attempt,
                   int maxAttempts, boolean retryAvailable) {
        this.sessionId = sessionId;
        this.workflow = workflow;
        this.state = state;
        this.message = message;
        this.guidance = guidance;
        this.tips = tips;
        this.currentChallenge = currentChallenge;
        this.challengeNumber = challengeNumber;
        this.challengeCount = challengeCount;
        this.progress = progress;
        this.challengeRemainingMs = challengeRemainingMs;
        this.attemptSequence = attemptSequence;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
        this.retryAvailable = retryAvailable;
    }

    public String getSessionId() { return sessionId; }
    public LivenessWorkflow getWorkflow() { return workflow; }
    public LivenessState getState() { return state; }
    /** Main instruction or result. */
    public UserMessage getMessage() { return message; }
    /** Secondary hint for the current frame (positioning, lighting), or null. */
    public UserMessage getGuidance() { return guidance; }
    /** Actionable tips after a failed attempt. */
    public List<UserMessage> getTips() { return tips; }
    /** Challenge being performed, or null outside active verification. */
    public ChallengeType getCurrentChallenge() { return currentChallenge; }
    /** 1-based number of the current challenge; 0 when no challenge has been issued. */
    public int getChallengeNumber() { return challengeNumber; }
    public int getChallengeCount() { return challengeCount; }
    /** Overall progress 0..1. */
    public double getProgress() { return progress; }
    /** Time left for the current challenge, or -1. */
    public long getChallengeRemainingMs() { return challengeRemainingMs; }
    /** Increments on every attempt, including those restarted after device faults. */
    public int getAttemptSequence() { return attemptSequence; }
    /** Attempt number counted against the retry limit (1-based), for "Attempt 2 of 3". */
    public int getAttempt() { return attempt; }
    public int getMaxAttempts() { return maxAttempts; }
    public boolean isRetryAvailable() { return retryAvailable; }
}
