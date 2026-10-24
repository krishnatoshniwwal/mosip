package io.mosip.registration.liveness.engine;

/**
 * Parts of the face an engine can report as covered (sunglasses, mask, scarf). Challenges that
 * need a covered region are not issued, and the subject is asked to uncover it.
 */
public enum FaceRegion {
    /** Both eyes hidden, e.g. sunglasses or tinted glasses. Clear prescription glasses do not count. */
    EYES,
    /** Nose and mouth hidden, e.g. a mask or scarf. */
    LOWER_FACE
}
