package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.InMemoryAuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngineException;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Drives a {@link LivenessSession} frame by frame on a virtual clock: deterministic and instant. */
final class SimulationHarness implements LivenessListener {

    static final int FPS = 15;

    final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    final SimulatedSubject subject;
    final MockFaceDevice device;
    final SimulatedLivenessEngine engine = new SimulatedLivenessEngine();
    final InMemoryAuditSink audit = new InMemoryAuditSink();
    final LivenessMetrics metrics = new LivenessMetrics();
    final List<LivenessUpdate> updates = new ArrayList<>();

    LivenessSession session;
    /** Answer to the camera notice given to each new session; null means "not asked". */
    CameraConsent consent = CameraConsent.granted("test-notice", "en", 0);
    LivenessResult result;
    boolean autoRetry = true;
    Consumer<LivenessUpdate> onUpdate = u -> { };
    /** Changes what the engine reports for each frame, e.g. a wrong PAD verdict. */
    UnaryOperator<FrameAnalysis> engineOverride = a -> a;

    SimulationHarness(long seed) {
        subject = new SimulatedSubject(clock::get, new Random(seed));
        subject.setNaturalBlinks(false);
        device = new MockFaceDevice(subject, FPS, DeviceLevel.L0, clock::get);
    }

    LivenessSession newSession(LivenessPolicy policy, long challengeSeed) {
        return newSession(policy, challengeSeed, engine.info());
    }

    LivenessSession newSession(LivenessPolicy policy, long challengeSeed, EngineInfo engineInfo) {
        return newSession(policy, challengeSeed, engineInfo, null, null);
    }

    /** A new session that shares {@code rateLimiter}; clears the previous result so {@link #run} can be reused. */
    LivenessSession newSession(LivenessPolicy policy, long challengeSeed, EngineInfo engineInfo,
                               AttemptRateLimiter rateLimiter, String rateLimitKey) {
        result = null;
        session = new LivenessSession("test-session", LivenessWorkflow.RESIDENT_REGISTRATION, policy, engineInfo,
                new ChallengeSelector(new Random(challengeSeed)), audit, metrics, subject.respondingTo(this),
                rateLimiter, rateLimitKey);
        if (consent != null) {
            session.setCameraConsent(consent.isGranted()
                    ? CameraConsent.granted(consent.getNoticeVersion(), consent.getLanguage(), clock.get())
                    : CameraConsent.declined(consent.getNoticeVersion(), consent.getLanguage(), clock.get()));
        }
        return session;
    }

    void start() {
        session.start(clock.get());
    }

    /** Runs until the session finishes (or, with autoRetry off, until an attempt fails). */
    LivenessResult run(long maxMillis) {
        long end = clock.get() + maxMillis;
        while (result == null && clock.get() < end) {
            if (session.state() == LivenessState.ATTEMPT_FAILED) {
                if (!autoRetry) {
                    return null;
                }
                session.retry(clock.get());
            }
            clock.addAndGet(1000 / FPS);
            FaceFrame frame = device.captureFrame();
            FrameAnalysis analysis;
            try {
                analysis = engine.analyze(frame);
            } catch (LivenessEngineException e) {
                throw new AssertionError(e);
            }
            session.onAnalysis(engineOverride.apply(analysis), frame, clock.get());
            session.onTick(clock.get());
        }
        return result;
    }

    LivenessResult startAndRun() {
        start();
        return run(120_000);
    }

    List<LivenessUpdate> updatesIn(LivenessState state) {
        List<LivenessUpdate> matching = new ArrayList<>();
        for (LivenessUpdate u : updates) {
            if (u.getState() == state) {
                matching.add(u);
            }
        }
        return matching;
    }

    @Override
    public void onUpdate(LivenessUpdate update) {
        updates.add(update);
        onUpdate.accept(update);
    }

    @Override
    public void onComplete(LivenessResult r) {
        result = r;
    }
}
