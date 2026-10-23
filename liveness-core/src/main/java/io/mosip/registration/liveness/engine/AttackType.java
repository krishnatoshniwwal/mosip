package io.mosip.registration.liveness.engine;

/**
 * Presentation attack instrument (PAI) species, after ISO/IEC 30107-1. Reported to audit and
 * diagnostics only; never shown to the person in front of the camera.
 */
public enum AttackType {
    NONE,
    PRINTED_PHOTO,
    SCREEN_DISPLAY,
    VIDEO_REPLAY,
    MASK,
    UNKNOWN
}
