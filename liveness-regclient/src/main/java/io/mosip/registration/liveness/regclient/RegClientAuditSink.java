package io.mosip.registration.liveness.regclient;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.audit.AuditSink;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends liveness audit events to the Registration Client's {@code AuditManagerService}, so they are stored and
 * synced with every other registration audit. The client's audit call takes its own {@code AuditEvent} and
 * {@code Components} enums, so this sink hands over their constant names and the client resolves them:
 *
 * <pre>
 * new RegClientAuditSink((event, component, refId, refIdType) -&gt; auditManagerService.audit(
 *         AuditEvent.valueOf(event), Components.valueOf(component), refId, refIdType));
 * </pre>
 *
 * The client's audit rows have a fixed description per event, so the event details (scores, reasons, which
 * check failed) go to the log only. No event carries biometric data.
 */
public final class RegClientAuditSink implements AuditSink {

    /** Receives the client's AuditEvent constant name, Components constant name, reference ID and its type. */
    @FunctionalInterface
    public interface AuditWriter {
        void write(String event, String component, String refId, String refIdType);
    }

    /** Prefix of the client {@code AuditEvent} constants added for liveness, e.g. {@code REG_LIVENESS_LIVENESS_PASSED}. */
    public static final String EVENT_PREFIX = "REG_LIVENESS_";
    /** Reference ID type for liveness rows; the reference ID is the liveness session ID. */
    public static final String REF_ID_TYPE = "LIVENESS_SESSION_ID";

    private static final Logger LOG = Logger.getLogger(RegClientAuditSink.class.getName());

    private final AuditWriter writer;

    public RegClientAuditSink(AuditWriter writer) {
        this.writer = writer;
    }

    @Override
    public void record(AuditEvent event) {
        LOG.info(() -> describe(event));
        try {
            writer.write(eventName(event.getType()), component(event.getWorkflow()), event.getSessionId(), REF_ID_TYPE);
        } catch (RuntimeException e) {
            // A failing audit store must not decide the liveness outcome; the log line above still has the event.
            LOG.log(Level.WARNING, "Could not write liveness audit event " + event.getType().code(), e);
        }
    }

    public static String eventName(AuditEventType type) {
        return EVENT_PREFIX + type.name();
    }

    /** The client module each workflow belongs to: biometrics capture, login, or operator/supervisor authentication. */
    public static String component(LivenessWorkflow workflow) {
        switch (workflow) {
            case RESIDENT_REGISTRATION:
                return "REG_BIOMETRICS";
            case OPERATOR_AUTHENTICATION:
                return "LOGIN";
            default:
                return "REG_OS_AUTH";
        }
    }

    static String describe(AuditEvent e) {
        return e.getType().code() + " " + e.getType() + " session=" + e.getSessionId() + " workflow="
                + e.getWorkflow().configKey() + " attempt=" + e.getAttempt()
                + (e.getDetails().isEmpty() ? "" : " " + e.getDetails());
    }
}
