package io.mosip.registration.liveness.gate;

import io.mosip.registration.liveness.session.FailureReason;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.UserMessage;

/** Outcome of a {@link LivenessGate} run: the liveness result and, if accepted, the capture it guarded. */
public final class GateResult<T> {

    private final GateStatus status;
    private final LivenessResult liveness;
    private final T capture;
    private final Throwable error;
    private final BindingViolation violation;
    private final long captureMs;
    private final int framesWatched;

    GateResult(GateStatus status, LivenessResult liveness, T capture, Throwable error, BindingViolation violation,
               long captureMs, int framesWatched) {
        this.status = status;
        this.liveness = liveness;
        this.capture = capture;
        this.error = error;
        this.violation = violation;
        this.captureMs = captureMs;
        this.framesWatched = framesWatched;
    }

    public boolean isAccepted() {
        return status == GateStatus.ACCEPTED;
    }

    public GateStatus getStatus() { return status; }
    /** The liveness session's result; its required action says what to do after the final failure. */
    public LivenessResult getLiveness() { return liveness; }
    /** What the capture step returned; null unless the capture completed. Use it only when accepted. */
    public T getCapture() { return capture; }
    /** What the capture step threw, for CAPTURE_FAILED. */
    public Throwable getError() { return error; }
    /** For BINDING_BROKEN: what was seen. For audit and diagnostics only; never show it to the user. */
    public BindingViolation getViolation() { return violation; }
    /** How long the capture step ran, or -1 if it did not run. */
    public long getCaptureMs() { return captureMs; }
    /** Frames analysed while the capture ran and right after it. */
    public int getFramesWatched() { return framesWatched; }

    /** What to tell the person. A broken binding gets the generic message, like a PAD failure. */
    public UserMessage getMessage() {
        switch (status) {
            case ACCEPTED:
                return UserMessage.SUCCESS;
            case BINDING_BROKEN:
                return UserMessage.FAIL_GENERIC;
            case CAPTURE_FAILED:
            case CAPTURE_TIMEOUT:
                return UserMessage.FAIL_DEVICE;
            case CANCELLED:
                return UserMessage.CANCELLED;
            default:
                FailureReason reason = liveness == null ? null : liveness.getFailureReason();
                return reason == null ? UserMessage.CANCELLED : reason.message();
        }
    }

    @Override
    public String toString() {
        return "GateResult{" + status + (violation == null ? "" : "," + violation)
                + (liveness == null ? "" : ",liveness=" + liveness.getOutcome()) + ",captureMs=" + captureMs + "}";
    }
}
