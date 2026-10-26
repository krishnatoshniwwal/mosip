package io.mosip.registration.liveness.gate;

/** Final status of a {@link LivenessGate} run. Only {@link #ACCEPTED} lets the workflow use the capture. */
public enum GateStatus {
    /** Liveness passed and the capture completed with the verified person still in view. */
    ACCEPTED,
    /** Liveness failed, was locked out or had no consent; the capture never ran. */
    LIVENESS_NOT_PASSED,
    /** Liveness passed, but the person in view changed, left or became an attack while the capture ran. */
    BINDING_BROKEN,
    /** The capture step threw. */
    CAPTURE_FAILED,
    /** The capture step took longer than {@code capture.bind.timeoutMs}. */
    CAPTURE_TIMEOUT,
    CANCELLED
}
