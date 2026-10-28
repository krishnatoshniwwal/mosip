package io.mosip.registration.liveness.config;

/** What a session does when the station has software (virtual) cameras. */
public enum VirtualCameraPolicy {
    /** Refuse to start when a virtual camera the capture backend can open is present. */
    BLOCK_REACHABLE,
    /** Refuse to start when any virtual camera software is installed, even one the backend cannot open. */
    BLOCK_INSTALLED,
    /** Only record the virtual cameras in the audit trail. */
    AUDIT
}
