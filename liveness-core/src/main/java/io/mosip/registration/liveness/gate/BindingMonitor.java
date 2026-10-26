package io.mosip.registration.liveness.gate;

import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.LivenessEngineException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches the held stream while the capture step runs, with the rules a session applies in every phase: PAD,
 * one face only, no face loss longer than 1.5 s, and the face must stay near where liveness was verified. The
 * first violation is kept. After the capture, {@link #awaitConfirmation} requires a fresh bona fide frame, so the
 * capture is bracketed by verified frames even on a device that pauses its stream while it captures.
 */
final class BindingMonitor implements FrameListener {

    private static final Logger LOG = Logger.getLogger(BindingMonitor.class.getName());
    /** Same as the session: a face may vanish this long (a blink of tracking) before it counts as gone. */
    static final long MAX_FACE_LOSS_MS = 1500;
    private static final int MAX_CONSECUTIVE_ENGINE_ERRORS = 3;

    private final LivenessEngine engine;
    private final LivenessPolicy policy;
    private final FaceObservation verifiedFace;
    private final LongSupplier clock;
    private final Consumer<FaceFrame> preview;
    private final ExecutorService worker;
    private final AtomicReference<FaceFrame> pending = new AtomicReference<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final CountDownLatch confirmed = new CountDownLatch(1);
    private final AtomicReference<BindingViolation> violation = new AtomicReference<>();

    private volatile long confirmAfter = Long.MAX_VALUE;
    private volatile long lastFaceAt;
    private volatile int framesAnalyzed;

    // Worker-thread state.
    private int attackFrames;
    private int engineErrors;

    /** @param verifiedFace the face in the frame the session accepted; null skips the position check */
    BindingMonitor(LivenessEngine engine, LivenessPolicy policy, FaceObservation verifiedFace, LongSupplier clock,
                   Consumer<FaceFrame> preview) {
        this.engine = engine;
        this.policy = policy;
        this.verifiedFace = verifiedFace;
        this.clock = clock;
        this.preview = preview;
        this.lastFaceAt = clock.getAsLong();
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "liveness-capture-bind");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onFrame(FaceFrame frame) {
        try {
            preview.accept(frame);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Preview listener failed", e);
        }
        pending.set(frame);
        if (drainScheduled.compareAndSet(false, true)) {
            try {
                worker.execute(this::drain);
            } catch (RejectedExecutionException e) {
                // closed: the capture is over
            }
        }
    }

    @Override
    public void onStreamError(DeviceException error) {
        violate(BindingViolation.STREAM_LOST);
    }

    @Override
    public void onStreamEnded() {
        violate(BindingViolation.STREAM_LOST);
    }

    private void drain() {
        drainScheduled.set(false);
        FaceFrame frame = pending.getAndSet(null);
        if (frame == null || violation.get() != null || !frame.isWellFormed()) {
            return;
        }
        FrameAnalysis analysis;
        try {
            analysis = engine.analyze(frame);
            engineErrors = 0;
        } catch (LivenessEngineException | RuntimeException e) {
            if (++engineErrors >= MAX_CONSECUTIVE_ENGINE_ERRORS) {
                violate(BindingViolation.ENGINE_ERROR);
            }
            return;
        }
        long now = clock.getAsLong();
        framesAnalyzed++;
        boolean attack = policy.isPadEnabled() && analysis.getPad().isAttackDetected();
        if (attack && ++attackFrames >= policy.getPadMinAttackFrames()) {
            violate(BindingViolation.ATTACK_DETECTED);
            return;
        }
        List<FaceObservation> faces = analysis.getFaces();
        if (faces.size() > 1) {
            violate(BindingViolation.MULTIPLE_FACES);
            return;
        }
        if (faces.isEmpty()) {
            checkFaceLoss(now);
            return;
        }
        FaceObservation face = faces.get(0);
        if (verifiedFace != null && face.distanceTo(verifiedFace) > policy.getCaptureBindMaxFaceShift()) {
            violate(BindingViolation.FACE_MOVED);
            return;
        }
        lastFaceAt = now;
        if (!attack && now >= confirmAfter) {
            confirmed.countDown();
        }
    }

    private void checkFaceLoss(long now) {
        if (now - lastFaceAt > MAX_FACE_LOSS_MS) {
            violate(BindingViolation.FACE_LOST);
        }
    }

    private void violate(BindingViolation v) {
        if (violation.compareAndSet(null, v)) {
            confirmed.countDown();
        }
    }

    /**
     * Waits for a bona fide frame of the person analysed at or after {@code since} (when the capture returned).
     *
     * @return the violation, or null if the person was confirmed in time
     */
    BindingViolation awaitConfirmation(long since, long timeoutMs) throws InterruptedException {
        confirmAfter = since;
        // A frame analysed between the capture returning and this call is not counted; the next one will be.
        if (!confirmed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            checkFaceLoss(clock.getAsLong());
            violate(BindingViolation.NOT_CONFIRMED);
        }
        return violation.get();
    }

    BindingViolation violation() {
        return violation.get();
    }

    int framesAnalyzed() {
        return framesAnalyzed;
    }

    /** Stops watching and waits briefly for a running analysis, so the engine is free for the next session. */
    void close() {
        worker.shutdownNow();
        try {
            worker.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
