package io.mosip.registration.liveness.gate;

/** Why the capture could not be tied to the person whose liveness was verified. Audit only; never shown. */
public enum BindingViolation {
    /** The engine flagged {@code pad.minAttackFrames} frames as an attack during the capture. */
    ATTACK_DETECTED,
    MULTIPLE_FACES,
    /** No face in view for longer than the session allows. */
    FACE_LOST,
    /** The face moved further than {@code capture.bind.maxFaceShift} from the verified position. */
    FACE_MOVED,
    /** The device stream stopped or failed, so the capture could not be watched. */
    STREAM_LOST,
    /** No bona fide frame of the person arrived after the capture finished. */
    NOT_CONFIRMED,
    /** The engine failed on several frames in a row. */
    ENGINE_ERROR
}
