package io.mosip.registration.liveness.device;

/** MOSIP SBI device certification level. */
public enum DeviceLevel {
    /** Signing key held in software on the host. */
    L0,
    /** Signing key held in a trusted execution environment / secure element. */
    L1
}
