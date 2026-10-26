package io.mosip.registration.liveness.gate;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.service.LivenessHandle;
import io.mosip.registration.liveness.session.CameraConsent;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessOutcome;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessUpdate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Puts liveness in front of a capture the Registration Client already makes. The MOSIP client stores the
 * MDS-signed {@code rCapture} image (and matches operators against a fresh capture), not a preview frame, so
 * liveness cannot simply hand over its own frame. The gate runs the liveness session on the device stream and
 * only then runs the capture step; while the step runs, the stream stays open and the same person must stay in
 * view, alone and bona fide ({@code capture.bind.*}). A capture taken from anyone or anything else is rejected.
 *
 * <pre>
 * GateHandle&lt;List&lt;BiometricsDto&gt;&gt; h = gate.start(RESIDENT_REGISTRATION, registrationId, consent, device,
 *         () -&gt; bioService.captureModality(mdmRequestDto), listener);
 * // listener.onGateComplete(r): use r.getCapture() only if r.isAccepted()
 * </pre>
 */
public final class LivenessGate {

    private static final Logger LOG = Logger.getLogger(LivenessGate.class.getName());
    /** How long to wait for a bona fide frame of the person after the capture returns. */
    static final long CONFIRM_TIMEOUT_MS = 2_000;

    private final FaceLivenessService service;
    private final LivenessConfig config;
    private final LivenessEngine engine;
    private final AuditSink audit;
    private final LongSupplier clock;

    /** @param service, config, engine and audit must be the ones the service was built with */
    public LivenessGate(FaceLivenessService service, LivenessConfig config, LivenessEngine engine, AuditSink audit) {
        this(service, config, engine, audit, System::currentTimeMillis);
    }

    LivenessGate(FaceLivenessService service, LivenessConfig config, LivenessEngine engine, AuditSink audit,
                 LongSupplier clock) {
        this.service = service;
        this.config = config;
        this.engine = engine;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Starts liveness on {@code device}; runs {@code capture} only if it passes.
     *
     * @param subjectKey as for {@link FaceLivenessService#start}: the user ID or registration ID failures count against
     */
    public <T> GateHandle<T> start(LivenessWorkflow workflow, String subjectKey, CameraConsent consent,
                                   FaceCaptureDevice device, CaptureStep<T> capture, GateListener<T> listener) {
        LivenessPolicy policy = config.policyFor(workflow);
        boolean bind = policy.isEnabled() && policy.isCaptureBindEnabled();
        HeldStreamDevice held = new HeldStreamDevice(device);
        GateHandle<T> handle = new GateHandle<>();
        AtomicReference<BindingMonitor> monitor = new AtomicReference<>();
        ExecutorService threads = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "liveness-gate");
            t.setDaemon(true);
            return t;
        });

        LivenessListener session = new LivenessListener() {
            @Override
            public void onUpdate(LivenessUpdate update) {
                listener.onUpdate(update);
            }

            @Override
            public void onPreviewFrame(FaceFrame frame) {
                listener.onPreviewFrame(frame);
            }

            @Override
            public void onComplete(LivenessResult result) {
                // Runs before the session releases the device: keep the stream for the capture.
                if (result.getOutcome() == LivenessOutcome.PASSED && bind && !handle.isCancelled()) {
                    BindingMonitor m = new BindingMonitor(engine, policy, result.getCapturedFace(), clock,
                            listener::onPreviewFrame);
                    if (held.hold(m)) {
                        monitor.set(m);
                    } else {
                        m.close();
                    }
                }
                listener.onComplete(result);
            }
        };

        LivenessHandle liveness = service.start(workflow, subjectKey, consent, held, session);
        handle.attach(liveness);
        liveness.result().whenComplete((result, error) -> threads.execute(() -> {
            GateResult<T> outcome;
            try {
                outcome = afterLiveness(workflow, subjectKey, policy, bind, result, monitor.get(), capture,
                        listener, handle, threads);
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Liveness gate failed", e);
                outcome = new GateResult<>(GateStatus.CAPTURE_FAILED, result, null, e, null, -1, 0);
            } finally {
                BindingMonitor m = monitor.get();
                if (m != null) {
                    m.close();
                }
                held.release();
            }
            try {
                listener.onGateComplete(outcome);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Gate listener failed in onGateComplete", e);
            }
            handle.result().complete(outcome);
            threads.shutdown();
        }));
        return handle;
    }

    private <T> GateResult<T> afterLiveness(LivenessWorkflow workflow, String subjectKey, LivenessPolicy policy,
                                            boolean bind, LivenessResult liveness, BindingMonitor monitor, CaptureStep<T> capture, GateListener<T> listener,
                                            GateHandle<T> handle, ExecutorService threads) {
        if (liveness == null || !liveness.isAccepted()) {
            boolean cancelled = liveness == null || liveness.getOutcome() == LivenessOutcome.CANCELLED;
            return new GateResult<>(cancelled ? GateStatus.CANCELLED : GateStatus.LIVENESS_NOT_PASSED,
                    liveness, null, null, null, -1, 0);
        }
        if (handle.isCancelled()) {
            return new GateResult<>(GateStatus.CANCELLED, liveness, null, null, null, -1, 0);
        }
        if (bind && monitor == null) {
            // The stream died between the last verified frame and the capture: nothing ties them together.
            return broken(workflow, subjectKey, policy, liveness, BindingViolation.STREAM_LOST, -1, 0);
        }

        try {
            listener.onCaptureStarted();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Gate listener failed in onCaptureStarted", e);
        }
        long started = clock.getAsLong();
        Future<T> future = threads.submit(capture::capture);
        handle.captureStarted(future);
        T value;
        try {
            value = future.get(policy.getCaptureBindTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return captureFailed(GateStatus.CAPTURE_TIMEOUT, liveness, null, clock.getAsLong() - started, monitor);
        } catch (ExecutionException e) {
            return captureFailed(GateStatus.CAPTURE_FAILED, liveness, e.getCause(), clock.getAsLong() - started, monitor);
        } catch (CancellationException e) {
            return new GateResult<>(GateStatus.CANCELLED, liveness, null, null, null, clock.getAsLong() - started,
                    frames(monitor));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new GateResult<>(GateStatus.CANCELLED, liveness, null, null, null, clock.getAsLong() - started,
                    frames(monitor));
        }
        long captureMs = clock.getAsLong() - started;

        if (!bind) {
            return new GateResult<>(GateStatus.ACCEPTED, liveness, value, null, null, captureMs, 0);
        }
        BindingViolation violation;
        try {
            violation = monitor.awaitConfirmation(clock.getAsLong(), CONFIRM_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GateResult<>(GateStatus.CANCELLED, liveness, null, null, null, captureMs, frames(monitor));
        }
        if (violation != null) {
            return broken(workflow, subjectKey, policy, liveness, violation, captureMs, monitor.framesAnalyzed());
        }
        audit(AuditEventType.CAPTURE_BOUND, liveness, "captureMs", String.valueOf(captureMs),
                "framesWatched", String.valueOf(monitor.framesAnalyzed()));
        return new GateResult<>(GateStatus.ACCEPTED, liveness, value, null, null, captureMs, monitor.framesAnalyzed());
    }

    /** The capture value is withheld: the caller must not be able to use a capture of the wrong person. */
    private <T> GateResult<T> broken(LivenessWorkflow workflow, String subjectKey, LivenessPolicy policy,
                                     LivenessResult liveness, BindingViolation violation, long captureMs, int frames) {
        audit(AuditEventType.CAPTURE_BINDING_BROKEN, liveness, "violation", violation.name(),
                "captureMs", String.valueOf(captureMs), "framesWatched", String.valueOf(frames));
        if (policy.isRateLimitEnabled()) {
            long until = service.rateLimiter().recordFailure(FaceLivenessService.rateLimitKey(workflow, subjectKey),
                    policy.getRateLimitMaxFailures(), policy.getRateLimitWindowMs(), policy.getRateLimitLockoutMs(),
                    clock.getAsLong());
            if (until > 0) {
                audit(AuditEventType.RATE_LIMIT_LOCKOUT, liveness, "lockedUntil", String.valueOf(until));
            }
        }
        return new GateResult<>(GateStatus.BINDING_BROKEN, liveness, null, null, violation, captureMs, frames);
    }

    private <T> GateResult<T> captureFailed(GateStatus status, LivenessResult liveness, Throwable error,
                                            long captureMs, BindingMonitor monitor) {
        audit(AuditEventType.CAPTURE_FAILED, liveness, "status", status.name(), "captureMs", String.valueOf(captureMs),
                "error", error == null ? "timeout" : error.getClass().getSimpleName());
        return new GateResult<>(status, liveness, null, error, null, captureMs, frames(monitor));
    }

    private static int frames(BindingMonitor monitor) {
        return monitor == null ? 0 : monitor.framesAnalyzed();
    }

    private void audit(AuditEventType type, LivenessResult liveness, String... keyValues) {
        Map<String, String> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            details.put(keyValues[i], keyValues[i + 1]);
        }
        try {
            audit.record(new AuditEvent(type, liveness.getSessionId(), liveness.getWorkflow(), liveness.getAttempts(),
                    Instant.ofEpochMilli(clock.getAsLong()), details));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Audit sink failed", e);
        }
    }
}
