package io.mosip.registration.liveness.session;

public enum LivenessState {
    NOT_STARTED(false, false),
    /** Collecting frames for the passive liveness and PAD decision. */
    PASSIVE_CHECK(true, false),
    /** Waiting for the subject to perform the current challenge. */
    ACTIVE_CHALLENGE(true, false),
    /** Brief "Action detected" pause between challenges. */
    CHALLENGE_FEEDBACK(true, false),
    /** Liveness established; selecting the best frontal frame to capture. */
    HOLD_STILL(true, false),
    /** This attempt failed; a retry is available. */
    ATTEMPT_FAILED(false, false),
    PASSED(false, true),
    FAILED(false, true),
    CANCELLED(false, true);

    private final boolean acceptsFrames;
    private final boolean terminal;

    LivenessState(boolean acceptsFrames, boolean terminal) {
        this.acceptsFrames = acceptsFrames;
        this.terminal = terminal;
    }

    public boolean acceptsFrames() {
        return acceptsFrames;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
