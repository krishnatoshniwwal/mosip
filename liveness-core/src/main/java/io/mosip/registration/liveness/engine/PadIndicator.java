package io.mosip.registration.liveness.engine;

/**
 * Evidence behind a PAD decision. Recorded in the audit trail for diagnostics; never shown to the
 * person in front of the camera.
 */
public enum PadIndicator {
    /** The anti-spoofing model classified the face as an attack. */
    MODEL,
    /** Periodic interference peaks in the face texture, typical of re-capturing a screen's pixel grid. */
    MOIRE,
    /** Flat, evenly emitted illumination with little 3D shading, typical of a backlit display. */
    UNIFORM_LIGHTING,
    /** Straight edges enclosing the face on several sides, typical of a phone, tablet or monitor bezel or a photo border. */
    BEZEL_EDGES
}
