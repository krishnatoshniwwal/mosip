package io.mosip.registration.liveness.regclient;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.gate.CaptureStep;
import io.mosip.registration.liveness.gate.GateHandle;
import io.mosip.registration.liveness.gate.GateListener;
import io.mosip.registration.liveness.gate.LivenessGate;
import io.mosip.registration.liveness.model.ModelSync;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.session.AttemptRateLimiter;
import io.mosip.registration.liveness.session.CameraConsent;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Everything one Registration Client needs for face liveness, built once at startup (a Spring {@code @Bean} in the
 * client) and shared by the resident capture, login and supervisor screens. Liveness runs on this machine with the
 * locally installed models, so the same checks apply whether the client is online or offline.
 */
public final class RegClientLiveness implements AutoCloseable {

    private final LivenessConfig config;
    private final LivenessEngine engine;
    private final FaceLivenessService service;
    private final LivenessGate gate;
    private volatile BooleanSupplier connectivity;
    private volatile ModelSync modelSync;
    private volatile ModelSync.Result lastModelSync;
    private ScheduledExecutorService syncScheduler;

    /**
     * @param rateLimitStore file that keeps failed-attempt counts and lockouts across client restarts (e.g. under
     *                       the client's {@code .mosipkeys} or DB folder); null keeps them in memory only
     */
    public RegClientLiveness(LivenessConfig config, LivenessEngine engine, AuditSink audit, Path rateLimitStore) {
        this.config = config;
        this.engine = engine;
        this.service = new FaceLivenessService(config, engine, audit, new LivenessMetrics(), new ChallengeSelector(),
                System::currentTimeMillis, rateLimitStore == null ? new AttemptRateLimiter()
                        : new AttemptRateLimiter(rateLimitStore));
        this.gate = new LivenessGate(service, config, engine, audit);
    }

    /**
     * Verifies liveness on {@code device}, then runs {@code capture} (the client's existing rCapture or face
     * authentication) only if it passed, watching the stream until the capture returns.
     *
     * @param subjectKey the registration ID for a resident, the user ID for an operator or supervisor
     */
    public <T> GateHandle<T> verifyThenCapture(LivenessWorkflow workflow, String subjectKey, CameraConsent consent,
                                               FaceCaptureDevice device, CaptureStep<T> capture,
                                               GateListener<T> listener) {
        return gate.start(workflow, subjectKey, consent, device, capture, listener);
    }

    /** Whether the workflow's policy has liveness switched on ({@code mosip.registration.liveness[.<workflow>].enabled}). */
    public boolean isEnabled(LivenessWorkflow workflow) {
        return config.policyFor(workflow).isEnabled();
    }

    /**
     * How the client knows it is online, e.g. {@code RegistrationAppHealthCheckUtil::isNetworkAvailable}. Used for
     * the capture screen's online/offline badge and to decide when to sync models; never for a liveness decision.
     */
    public void setConnectivity(BooleanSupplier isOnline) {
        connectivity = isOnline;
    }

    /** @return true/false, or null if no connectivity check was set. A failing check counts as offline. */
    public Boolean isOnline() {
        BooleanSupplier check = connectivity;
        if (check == null) {
            return null;
        }
        try {
            return check.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Checks the update server for a newer signed model package every {@code intervalMinutes} while online (and
     * once now). A package installed this way is used from the next client start; offline, nothing is attempted.
     */
    public synchronized void startModelSync(ModelSync sync, long intervalMinutes) {
        if (syncScheduler != null) {
            throw new IllegalStateException("Model sync already started");
        }
        modelSync = sync;
        syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "liveness-model-sync");
            t.setDaemon(true);
            return t;
        });
        syncScheduler.scheduleWithFixedDelay(this::syncModelsIfOnline, 0, intervalMinutes, TimeUnit.MINUTES);
    }

    /**
     * One sync attempt if a sync is configured and the client is online.
     *
     * @return the result, or null if nothing was attempted (offline, or no sync configured)
     */
    public ModelSync.Result syncModelsIfOnline() {
        ModelSync sync = modelSync;
        if (sync == null || !Boolean.TRUE.equals(isOnline())) {
            return null;
        }
        ModelSync.Result result = sync.sync();
        lastModelSync = result;
        return result;
    }

    /** Package-private for tests and {@link #startModelSync}: sets the sync without scheduling it. */
    void setModelSync(ModelSync sync) {
        modelSync = sync;
    }

    /** The last sync attempt, or null if none ran yet (for diagnostics). */
    public ModelSync.Result lastModelSync() {
        return lastModelSync;
    }

    public LivenessConfig config() {
        return config;
    }

    public LivenessEngine engine() {
        return engine;
    }

    public FaceLivenessService service() {
        return service;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (syncScheduler != null) {
                syncScheduler.shutdownNow();
            }
        }
        engine.close();
    }
}
