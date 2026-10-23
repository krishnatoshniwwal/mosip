package io.mosip.registration.liveness.audit;

import io.mosip.registration.liveness.LivenessWorkflow;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One audit record. Holds decisions, scores and reasons only; never images, templates or
 * landmarks, so audit logs carry no biometric data.
 */
public final class AuditEvent {
    private final AuditEventType type;
    private final String sessionId;
    private final LivenessWorkflow workflow;
    private final int attempt;
    private final Instant timestamp;
    private final Map<String, String> details;

    public AuditEvent(AuditEventType type, String sessionId, LivenessWorkflow workflow, int attempt,
                      Instant timestamp, Map<String, String> details) {
        this.type = type;
        this.sessionId = sessionId;
        this.workflow = workflow;
        this.attempt = attempt;
        this.timestamp = timestamp;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public AuditEventType getType() { return type; }
    public String getSessionId() { return sessionId; }
    public LivenessWorkflow getWorkflow() { return workflow; }
    public int getAttempt() { return attempt; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, String> getDetails() { return details; }

    @Override
    public String toString() {
        return timestamp + " " + type.code() + " " + type + " session=" + sessionId + " workflow=" + workflow
                + " attempt=" + attempt + (details.isEmpty() ? "" : " " + details);
    }
}
