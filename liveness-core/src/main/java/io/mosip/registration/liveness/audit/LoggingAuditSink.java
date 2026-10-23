package io.mosip.registration.liveness.audit;

import java.util.logging.Logger;

/** Writes audit events to {@code java.util.logging} under {@code io.mosip.registration.liveness.audit}. */
public final class LoggingAuditSink implements AuditSink {

    private static final Logger LOG = Logger.getLogger("io.mosip.registration.liveness.audit");

    @Override
    public void record(AuditEvent event) {
        LOG.info(event::toString);
    }
}
