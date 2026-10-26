package io.mosip.registration.liveness.regclient;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.config.ConfigException;
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.config.LivenessConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Configuration from the client's global parameters, and audit into the client's audit manager. */
class RegClientIntegrationTest {

    @Test
    void configComesFromTheSyncedGlobalParameters() {
        Map<String, Object> applicationMap = new HashMap<>();
        applicationMap.put("mosip.registration.liveness.passive.threshold", 75);
        applicationMap.put("mosip.registration.liveness.supervisor.active.always", " true ");
        applicationMap.put("mosip.registration.liveness.operator.retry.exhaustedAction", "ALLOW_ALTERNATE_AUTH");
        applicationMap.put("mosip.registration.face.timeout", "30000");
        applicationMap.put("mosip.registration.liveness.capture.bind.timeoutMs", null);

        LivenessConfig config = RegClientLivenessConfig.fromApplicationMap(applicationMap);

        assertEquals(75, config.policyFor(LivenessWorkflow.RESIDENT_REGISTRATION).getPassiveThreshold());
        assertTrue(config.policyFor(LivenessWorkflow.SUPERVISOR_AUTHENTICATION).isActiveAlways());
        assertFalse(config.policyFor(LivenessWorkflow.OPERATOR_AUTHENTICATION).isActiveAlways());
        assertEquals(FailureAction.ALLOW_ALTERNATE_AUTH,
                config.policyFor(LivenessWorkflow.OPERATOR_AUTHENTICATION).getRetryExhaustedAction());
    }

    @Test
    void syncedValuesOverrideTheBundledFile() {
        Properties base = new Properties();
        base.setProperty("mosip.registration.liveness.passive.threshold", "70");
        base.setProperty("mosip.registration.liveness.retry.max", "4");

        LivenessConfig config = RegClientLivenessConfig.fromApplicationMap(
                Collections.singletonMap("mosip.registration.liveness.passive.threshold", "80"), base);

        assertEquals(80, config.policyFor(LivenessWorkflow.RESIDENT_REGISTRATION).getPassiveThreshold());
        assertEquals(4, config.policyFor(LivenessWorkflow.RESIDENT_REGISTRATION).getMaxRetries());
    }

    @Test
    void aMistypedServerSettingIsRejected() {
        assertThrows(ConfigException.class, () -> RegClientLivenessConfig.fromApplicationMap(
                Collections.singletonMap("mosip.registration.liveness.pasive.threshold", "10")));
    }

    @Test
    void auditEventsReachTheClientAuditManagerWithoutDetails() {
        List<String> rows = new ArrayList<>();
        RegClientAuditSink sink = new RegClientAuditSink(
                (event, component, refId, refIdType) -> rows.add(event + "|" + component + "|" + refId + "|" + refIdType));

        sink.record(event(AuditEventType.LIVENESS_PASSED, LivenessWorkflow.RESIDENT_REGISTRATION));
        sink.record(event(AuditEventType.CAPTURE_BINDING_BROKEN, LivenessWorkflow.SUPERVISOR_AUTHENTICATION));
        sink.record(event(AuditEventType.PAD_ATTACK_DETECTED, LivenessWorkflow.OPERATOR_AUTHENTICATION));

        assertEquals("REG_LIVENESS_LIVENESS_PASSED|REG_BIOMETRICS|session-1|LIVENESS_SESSION_ID", rows.get(0));
        assertEquals("REG_LIVENESS_CAPTURE_BINDING_BROKEN|REG_OS_AUTH|session-1|LIVENESS_SESSION_ID", rows.get(1));
        assertEquals("REG_LIVENESS_PAD_ATTACK_DETECTED|LOGIN|session-1|LIVENESS_SESSION_ID", rows.get(2));
    }

    @Test
    void aFailingAuditStoreDoesNotBreakLiveness() {
        RegClientAuditSink sink = new RegClientAuditSink((event, component, refId, refIdType) -> {
            throw new IllegalStateException("audit DB locked");
        });
        sink.record(event(AuditEventType.SESSION_STARTED, LivenessWorkflow.RESIDENT_REGISTRATION));
    }

    private static AuditEvent event(AuditEventType type, LivenessWorkflow workflow) {
        return new AuditEvent(type, "session-1", workflow, 1, Instant.now(), Collections.singletonMap("score", "81.0"));
    }
}
