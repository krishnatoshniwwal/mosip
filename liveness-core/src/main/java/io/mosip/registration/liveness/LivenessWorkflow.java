package io.mosip.registration.liveness;

/**
 * Registration Client workflows in which face liveness is enforced. Each workflow can carry
 * its own {@link io.mosip.registration.liveness.config.LivenessPolicy}.
 */
public enum LivenessWorkflow {
    RESIDENT_REGISTRATION("resident"),
    OPERATOR_AUTHENTICATION("operator"),
    SUPERVISOR_AUTHENTICATION("supervisor");

    private final String configKey;

    LivenessWorkflow(String configKey) {
        this.configKey = configKey;
    }

    /**
     * Segment used for workflow-specific overrides, e.g.
     * {@code mosip.registration.liveness.operator.passive.threshold}.
     */
    public String configKey() {
        return configKey;
    }
}
