package io.mosip.registration.liveness.regclient;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.evidence.EvidenceSigner;
import io.mosip.registration.liveness.evidence.LivenessEvidence;
import io.mosip.registration.liveness.evidence.LivenessEvidenceJws;
import io.mosip.registration.liveness.gate.CaptureStep;
import io.mosip.registration.liveness.gate.GateHandle;
import io.mosip.registration.liveness.gate.GateListener;
import io.mosip.registration.liveness.gate.GateResult;
import io.mosip.registration.liveness.gate.LivenessGate;
import io.mosip.registration.liveness.injection.CameraSourceCheck;
import io.mosip.registration.liveness.model.ModelSync;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.session.AttemptRateLimiter;
import io.mosip.registration.liveness.session.CameraConsent;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
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
    private volatile EvidenceSigner evidenceSigner;
    private volatile String stationId;

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

    /**
     * Lists where a local camera's frames can come from before each session, so a station with a software camera
     * is refused (see {@code injection.virtualCameras}). Not needed for SBI devices, which the MDS vouches for.
     */
    public void setCameraSourceCheck(CameraSourceCheck check) {
        service.setCameraSourceCheck(check);
    }

    /** Whether the workflow's policy has liveness switched on ({@code mosip.registration.liveness[.<workflow>].enabled}). */
    public boolean isEnabled(LivenessWorkflow workflow) {
        return config.policyFor(workflow).isEnabled();
    }

    /**
     * Signs liveness evidence with the station's key, e.g. the TPM machine key through {@code ClientCryptoFacade}.
     * Without a signer, {@link #issueEvidence} returns null and packets carry no evidence.
     *
     * @param stationId the machine ID registered on the server, which identifies the public key to check with
     */
    public void setEvidenceSigner(EvidenceSigner signer, String stationId) {
        this.evidenceSigner = signer;
        this.stationId = stationId;
    }

    /**
     * Signed evidence of what liveness verified for this capture (a compact JWS), for the registration packet
     * next to the face it names. Issue it for failed runs too: a resident sent to supervisor review then carries
     * a signed record that liveness did not pass.
     *
     * @param capturedBiometric the face bytes the packet stores (the rCapture {@code bioValue}), or null
     * @return the JWS, or null if no signer is set
     */
    public String issueEvidence(GateResult<?> result, byte[] capturedBiometric, String subjectKey)
            throws GeneralSecurityException {
        EvidenceSigner signer = evidenceSigner;
        if (signer == null) {
            return null;
        }
        LivenessEvidence evidence = LivenessEvidence.of(result, capturedBiometric, subjectKey, stationId,
                engine.info().getModelVersion(), Instant.now());
        return LivenessEvidenceJws.sign(evidence, signer);
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
