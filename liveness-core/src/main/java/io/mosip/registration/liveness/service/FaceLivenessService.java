package io.mosip.registration.liveness.service;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.injection.CameraSourceCheck;
import io.mosip.registration.liveness.session.AttemptRateLimiter;
import io.mosip.registration.liveness.session.CameraConsent;
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
 *
 * <p>Failed attempts are rate limited across sessions per workflow and subject key (see
 * {@link #start(LivenessWorkflow, String, FaceCaptureDevice, LivenessListener)}).
 */
public final class FaceLivenessService {

    private final LivenessConfig config;
    private final LivenessEngine engine;
    private final AuditSink audit;
    private final LivenessMetrics metrics;
    private final ChallengeSelector selector;
    private final LongSupplier clock;
    private final AttemptRateLimiter rateLimiter;
    private volatile CameraSourceCheck cameraSourceCheck = CameraSourceCheck.NONE;

    /** Rate-limit state is kept in memory; use the other constructor to persist it across restarts. */
    public FaceLivenessService(LivenessConfig config, LivenessEngine engine, AuditSink audit) {
        this(config, engine, audit, new LivenessMetrics(), new ChallengeSelector(), System::currentTimeMillis,
                new AttemptRateLimiter());
    }

    public FaceLivenessService(LivenessConfig config, LivenessEngine engine, AuditSink audit, LivenessMetrics metrics,
                               ChallengeSelector selector, LongSupplier clock) {
        this(config, engine, audit, metrics, selector, clock, new AttemptRateLimiter());
    }

    public FaceLivenessService(LivenessConfig config, LivenessEngine engine, AuditSink audit, LivenessMetrics metrics,
                               ChallengeSelector selector, LongSupplier clock, AttemptRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
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
    /** Without a consent: fails at once with {@code CONSENT_DECLINED} unless the policy has {@code consent.required=false}. */
    public LivenessHandle start(LivenessWorkflow workflow, FaceCaptureDevice device, LivenessListener listener) {
        return start(workflow, null, null, device, listener);
    }

    public LivenessHandle start(LivenessWorkflow workflow, CameraConsent consent, FaceCaptureDevice device,
                                LivenessListener listener) {
        return start(workflow, null, consent, device, listener);
    }

    public LivenessHandle start(LivenessWorkflow workflow, String subjectKey, FaceCaptureDevice device,
                                LivenessListener listener) {
        return start(workflow, subjectKey, null, device, listener);
    }

    /**
     * @param subjectKey who failed attempts are counted against for rate limiting: the operator or
     *                   supervisor user ID for authentication, the registration ID for a resident; null
     *                   counts against the whole station for this workflow
     * @param consent    the person's answer to the camera notice; the device is opened only if the policy does
     *                   not require consent or this is a fresh, granted consent
     */
    public LivenessHandle start(LivenessWorkflow workflow, String subjectKey, CameraConsent consent,
                                FaceCaptureDevice device, LivenessListener listener) {
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
                config.policyFor(workflow), engine.info(), selector, audit, metrics, recording,
                rateLimiter, rateLimitKey(workflow, subjectKey));
        session.setCameraConsent(consent);
        LivenessHandle handle = new LivenessHandle(session, device, engine, metrics, clock, recording, finished,
                cameraSourceCheck);
        handle.begin();
        return handle;
    }

    /**
     * Lists where a device's frames can come from before each session opens it, so that a station with a software
     * camera (the usual way to inject a deepfake) can be refused. Defaults to {@link CameraSourceCheck#NONE}.
     */
    public void setCameraSourceCheck(CameraSourceCheck check) {
        this.cameraSourceCheck = check == null ? CameraSourceCheck.NONE : check;
    }

    /** The key failed attempts are counted under in {@link #rateLimiter()}. */
    public static String rateLimitKey(LivenessWorkflow workflow, String subjectKey) {
        return workflow.configKey() + ":" + (subjectKey == null ? "*station*" : subjectKey);
    }

    public AttemptRateLimiter rateLimiter() {
        return rateLimiter;
    }

    public LivenessMetrics metrics() {
        return metrics;
    }
}
