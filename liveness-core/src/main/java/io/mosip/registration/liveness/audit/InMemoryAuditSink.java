package io.mosip.registration.liveness.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps events in memory; for tests and the diagnostic view. */
public final class InMemoryAuditSink implements AuditSink {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    public List<AuditEvent> events() {
        return new ArrayList<>(events);
    }

    public List<AuditEvent> ofType(AuditEventType type) {
        List<AuditEvent> matching = new ArrayList<>();
        for (AuditEvent event : events) {
            if (event.getType() == type) {
                matching.add(event);
            }
        }
        return matching;
    }

    public void clear() {
        events.clear();
    }
}
