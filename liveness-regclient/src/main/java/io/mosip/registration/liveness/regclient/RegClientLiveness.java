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
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.session.AttemptRateLimiter;
import io.mosip.registration.liveness.session.CameraConsent;

import java.nio.file.Path;

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
        engine.close();
    }
}
