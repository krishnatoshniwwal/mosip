package io.mosip.registration.liveness.gate;

/**
 * The capture or authentication the Registration Client already performs, run by {@link LivenessGate} only
 * after liveness has passed: MDS {@code rCapture} for a resident, {@code captureAndValidateFace} or
 * {@code SessionContext.create} for an operator or supervisor. It runs on a gate thread and may block.
 */
@FunctionalInterface
public interface CaptureStep<T> {

    T capture() throws Exception;
}
