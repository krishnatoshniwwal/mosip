package io.mosip.registration.liveness.session;

public enum LivenessOutcome {
    PASSED,
    FAILED,
    CANCELLED,
    /** Liveness is switched off by policy; a face was captured without liveness or PAD checks. */
    LIVENESS_DISABLED
}
