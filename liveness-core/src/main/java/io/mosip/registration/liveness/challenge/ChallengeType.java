package io.mosip.registration.liveness.challenge;

import java.util.Locale;

/**
 * Facial actions the Registration Client can ask for during active liveness verification.
 * The set actually used is the intersection of the configured types and the types the
 * liveness engine reports it can verify.
 */
public enum ChallengeType {
    BLINK,
    SMILE,
    TURN_LEFT,
    TURN_RIGHT,
    LOOK_UP,
    LOOK_DOWN;

    public static ChallengeType parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
