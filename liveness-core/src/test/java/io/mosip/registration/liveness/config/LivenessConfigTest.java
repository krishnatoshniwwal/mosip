package io.mosip.registration.liveness.config;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.challenge.ChallengeType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivenessConfigTest {

    private static final String P = LivenessConfig.PREFIX;

    @Test
    void defaultsAreSecure() {
        LivenessPolicy policy = LivenessConfig.defaults().policyFor(LivenessWorkflow.RESIDENT_REGISTRATION);
        assertTrue(policy.isEnabled());
        assertTrue(policy.isPadEnabled());
        assertTrue(policy.isActiveEnabled());
        assertEquals(70, policy.getPassiveThreshold());
        assertTrue(policy.getMinChallenges() >= 2);
        assertEquals(FailureAction.BLOCK, policy.getRetryExhaustedAction());
    }

    @Test
    void workflowOverridesFallBackToGlobalValues() {
        Properties props = new Properties();
        props.setProperty(P + "passive.threshold", "65");
        props.setProperty(P + "supervisor.passive.threshold", "85");
        props.setProperty(P + "supervisor.active.always", "true");
        props.setProperty(P + "operator.retry.exhaustedAction", "allow_alternate_auth");
        props.setProperty(P + "active.challenges", "blink, turn_left ,TURN_RIGHT");

        LivenessConfig config = LivenessConfig.fromProperties(props);

        LivenessPolicy resident = config.policyFor(LivenessWorkflow.RESIDENT_REGISTRATION);
        LivenessPolicy operator = config.policyFor(LivenessWorkflow.OPERATOR_AUTHENTICATION);
        LivenessPolicy supervisor = config.policyFor(LivenessWorkflow.SUPERVISOR_AUTHENTICATION);
        assertEquals(65, resident.getPassiveThreshold());
        assertFalse(resident.isActiveAlways());
        assertEquals(85, supervisor.getPassiveThreshold());
        assertTrue(supervisor.isActiveAlways());
        assertEquals(FailureAction.ALLOW_ALTERNATE_AUTH, operator.getRetryExhaustedAction());
        assertEquals(FailureAction.BLOCK, resident.getRetryExhaustedAction());
        assertEquals(Arrays.asList(ChallengeType.BLINK, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT),
                operator.getChallengeTypes());
    }

    @Test
    void unknownKeysAreRejected() {
        Properties props = new Properties();
        props.setProperty(P + "passive.treshold", "10");
        ConfigException e = assertThrows(ConfigException.class, () -> LivenessConfig.fromProperties(props));
        assertTrue(e.getMessage().contains("passive.treshold"));
    }

    @Test
    void unrelatedKeysAreIgnored() {
        Properties props = new Properties();
        props.setProperty("mosip.registration.something.else", "x");
        LivenessConfig.fromProperties(props);
    }

    @Test
    void invalidValuesNameTheKey() {
        Properties props = new Properties();
        props.setProperty(P + "operator.pad.enabled", "yes");
        ConfigException e = assertThrows(ConfigException.class, () -> LivenessConfig.fromProperties(props));
        assertTrue(e.getMessage().contains("operator.pad.enabled"));
    }

    @Test
    void inconsistentPoliciesAreRejected() {
        assertInvalid(P + "passive.threshold", "120");
        assertInvalid(P + "active.minChallenges", "4");
        assertInvalid(P + "active.challenges", "BLINK");
        assertInvalid(P + "active.challenges", "WINK");
        assertInvalid(P + "retry.max", "-1");

        Properties props = new Properties();
        props.setProperty(P + "active.enabled", "false");
        props.setProperty(P + "active.always", "true");
        assertThrows(ConfigException.class, () -> LivenessConfig.fromProperties(props));
    }

    @Test
    void captureBindingIsOnByDefaultAndConfigurablePerWorkflow() {
        assertTrue(LivenessConfig.defaults().policyFor(LivenessWorkflow.RESIDENT_REGISTRATION).isCaptureBindEnabled());

        Properties props = new Properties();
        props.setProperty(P + "capture.bind.timeoutMs", "15000");
        props.setProperty(P + "operator.capture.bind.maxFaceShift", "0.15");
        LivenessConfig config = LivenessConfig.fromProperties(props);
        LivenessPolicy operator = config.policyFor(LivenessWorkflow.OPERATOR_AUTHENTICATION);
        assertEquals(15000, operator.getCaptureBindTimeoutMs());
        assertEquals(0.15f, operator.getCaptureBindMaxFaceShift(), 1e-6);
        assertEquals(0.25f, config.policyFor(LivenessWorkflow.RESIDENT_REGISTRATION).getCaptureBindMaxFaceShift(), 1e-6);

        assertInvalid(P + "capture.bind.timeoutMs", "0");
        assertInvalid(P + "capture.bind.maxFaceShift", "1.5");
    }

    private static void assertInvalid(String key, String value) {
        Properties props = new Properties();
        props.setProperty(key, value);
        assertThrows(ConfigException.class, () -> LivenessConfig.fromProperties(props), key + "=" + value);
    }
}
