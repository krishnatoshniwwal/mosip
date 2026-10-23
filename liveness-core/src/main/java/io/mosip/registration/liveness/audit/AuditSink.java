package io.mosip.registration.liveness.audit;

/**
 * Destination for liveness audit events. In the Registration Client this is adapted to the existing
 * audit manager so events land in the same (synced) audit table as other registration events.
 */
public interface AuditSink {

    AuditSink NONE = event -> { };

    void record(AuditEvent event);
}
