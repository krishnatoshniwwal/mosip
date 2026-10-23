package io.mosip.registration.liveness.service;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessSession;
import io.mosip.registration.liveness.session.LivenessUpdate;
import io.mosip.registration.liveness.device.FaceFrame;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Entry point for the Registration Client. Resident face capture, operator login and supervisor
 * authentication all call {@link #start} with their workflow; the policy for that workflow decides
 * thresholds, challenges and retries.
 *
 * <pre>
 * LivenessHandle h = service.start(LivenessWorkflow.OPERATOR_AUTHENTICATION, device, listener);
 * // listener.onUpdate drives the UI; listener.onComplete delivers the result
 * h.retry();  // when the user taps Retry after a failed attempt
 * </pre>
 */
public final class FaceLivenessService {

    private final LivenessConfig config;
    private final LivenessEngine engine;
    private final AuditSink audit;
    private final LivenessMetrics metrics;
    private final ChallengeSelector selector;
    private final LongSupplier clock;

    public FaceLivenessService(LivenessConfig config, LivenessEngine engine, AuditSink audit) {
        this(config, engine, audit, new LivenessMetrics(), new ChallengeSelector(), System::currentTimeMillis);
    }

    public FaceLivenessService(LivenessConfig config, LivenessEngine engine, AuditSink audit, LivenessMetrics metrics,
                               ChallengeSelector selector, LongSupplier clock) {
        this.config = config;
        this.engine = engine;
        this.audit = audit;
        this.metrics = metrics;
        this.selector = selector;
        this.clock = clock;
    }

    /**
     * Opens the device, starts streaming and begins liveness verification. The device is stopped and
     * closed when the session ends.
     */
    public LivenessHandle start(LivenessWorkflow workflow, FaceCaptureDevice device, LivenessListener listener) {
        AtomicReference<LivenessResult> finished = new AtomicReference<>();
        LivenessListener recording = new LivenessListener() {
            @Override
            public void onUpdate(LivenessUpdate update) {
                listener.onUpdate(update);
            }

            @Override
            public void onComplete(LivenessResult r) {
                finished.set(r);
                listener.onComplete(r);
            }

            @Override
            public void onPreviewFrame(FaceFrame frame) {
                listener.onPreviewFrame(frame);
            }
        };
        LivenessSession session = new LivenessSession(UUID.randomUUID().toString(), workflow,
                config.policyFor(workflow), engine.info(), selector, audit, metrics, recording);
        LivenessHandle handle = new LivenessHandle(session, device, engine, metrics, clock, recording, finished);
        handle.begin();
        return handle;
    }

    public LivenessMetrics metrics() {
        return metrics;
    }
}
