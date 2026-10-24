package io.mosip.registration.liveness.challenge;

import io.mosip.registration.liveness.engine.FaceRegion;

import java.util.Locale;

/**
 * Facial actions the Registration Client can ask for during active liveness verification.
 * The set actually used is the intersection of the configured types and the types the
 * liveness engine reports it can verify.
 */
public enum ChallengeType {
    BLINK(FaceRegion.EYES),
    SMILE(FaceRegion.LOWER_FACE),
    TURN_LEFT(null),
    TURN_RIGHT(null),
    LOOK_UP(null),
    LOOK_DOWN(null);

    private final FaceRegion requiredRegion;

    ChallengeType(FaceRegion requiredRegion) {
        this.requiredRegion = requiredRegion;
    }

    /** Face region that must be visible to verify this action, or null for head movements. */
    public FaceRegion requiredRegion() {
        return requiredRegion;
    }

    public static ChallengeType parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
