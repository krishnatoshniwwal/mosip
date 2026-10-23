package io.mosip.registration.liveness.config;

/** What the Registration Client must do once the liveness retry budget is exhausted. */
public enum FailureAction {
    /** Stop the workflow; the user must contact an administrator. */
    BLOCK,
    /** Resident capture only: continue only after a supervisor reviews and approves (exception flow). */
    REQUIRE_SUPERVISOR_OVERRIDE,
    /** Operator/supervisor login: fall back to another configured authentication method. */
    ALLOW_ALTERNATE_AUTH
}
