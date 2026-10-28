package io.mosip.registration.liveness.service;

import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.LivenessEngineException;
import io.mosip.registration.liveness.injection.CameraSourceCheck;
import io.mosip.registration.liveness.injection.CameraSourceReport;
import io.mosip.registration.liveness.session.FailureReason;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessSession;
import io.mosip.registration.liveness.session.LivenessState;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A running liveness session bound to a device. All session work runs on one worker thread.
 * When analysis is slower than the device frame rate, only the newest frame is kept (older ones
 * are dropped and counted), so latency stays bounded on low-end hardware.
 */
public final class LivenessHandle {

    private static final Logger LOG = Logger.getLogger(LivenessHandle.class.getName());
    private static final long TICK_MS = 100;
    private static final int MAX_CONSECUTIVE_INVALID_FRAMES = 5;
    private static final int MAX_CONSECUTIVE_ENGINE_ERRORS = 3;

    private final LivenessSession session;
    private final FaceCaptureDevice device;
    private final LivenessEngine engine;
    private final LivenessMetrics metrics;
    private final LongSupplier clock;
    private final LivenessListener listener;
    private final AtomicReference<LivenessResult> finished;
    private final CameraSourceCheck sourceCheck;
    private final CompletableFuture<LivenessResult> result = new CompletableFuture<>();

    private final ExecutorService worker;
    private final ScheduledExecutorService ticker;
    private final AtomicReference<FaceFrame> pending = new AtomicReference<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final FrameListener frameListener = new DeviceFrames();

    // Worker-thread state.
    private boolean streaming;
    private boolean released;
    private int consecutiveInvalidFrames;
    private int consecutiveEngineErrors;

    LivenessHandle(LivenessSession session, FaceCaptureDevice device, LivenessEngine engine, LivenessMetrics metrics,
                   LongSupplier clock, LivenessListener listener, AtomicReference<LivenessResult> finished,
                   CameraSourceCheck sourceCheck) {
        this.session = session;
        this.device = device;
        this.engine = engine;
        this.metrics = metrics;
        this.clock = clock;
        this.listener = listener;
        this.finished = finished;
        this.sourceCheck = sourceCheck;
        String name = "liveness-" + session.getSessionId().substring(0, 8);
        this.worker = Executors.newSingleThreadExecutor(r -> daemon(r, name));
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, name + "-tick"));
    }

    void begin() {
        // Schedule the ticker before the start task: a session can end inside start (no consent, locked out),
        // and afterStep() then shuts the ticker down. Ticks before start are ignored by the session.
        ticker.scheduleAtFixedRate(() -> submit(() -> {
            session.onTick(clock.getAsLong());
            afterStep();
        }), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        submit(() -> {
            session.setCameraSourceReport(inspectSource());
            session.start(clock.getAsLong());
            // Never touch the camera for a session that ended at start (no consent, locked out).
            if (!session.state().isTerminal()) {
                openAndStream();
            }
            afterStep();
        });
    }

    public String getSessionId() {
        return session.getSessionId();
    }

    public LivenessState state() {
        return session.state();
    }

    /** Completes once with the final result (PASSED, FAILED or CANCELLED), after the device is released. */
    public CompletableFuture<LivenessResult> result() {
        return result;
    }

    /** Starts the next attempt after a failed one, reconnecting the device if needed. */
    public void retry() {
        submit(() -> {
            if (session.retry(clock.getAsLong()) && !streaming) {
                openAndStream();
            }
            afterStep();
        });
    }

    public void cancel() {
        submit(() -> {
            session.cancel(clock.getAsLong());
            afterStep();
        });
    }

    /** Lists the device's possible frame sources without opening it; a failed listing does not stop the session. */
    private CameraSourceReport inspectSource() {
        try {
            return sourceCheck.inspect(device.info());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Camera source check failed", e);
            return CameraSourceReport.failed("check failed: " + e.getMessage());
        }
    }

    private void openAndStream() {
        try {
            device.open();
            device.startStream(frameListener);
            streaming = true;
        } catch (DeviceException e) {
            streaming = false;
            session.onSystemError(reasonFor(e), e.getMessage(), clock.getAsLong());
        }
    }

    private void drain() {
        drainScheduled.set(false);
        FaceFrame frame = pending.getAndSet(null);
        if (frame == null || !session.state().acceptsFrames()) {
            return;
        }
        if (!frame.isWellFormed()) {
            if (++consecutiveInvalidFrames >= MAX_CONSECUTIVE_INVALID_FRAMES) {
                consecutiveInvalidFrames = 0;
                session.onSystemError(FailureReason.INVALID_FRAME_DATA,
                        MAX_CONSECUTIVE_INVALID_FRAMES + " consecutive malformed frames", clock.getAsLong());
                afterStep();
            }
            return;
        }
        consecutiveInvalidFrames = 0;
        long started = System.nanoTime();
        FrameAnalysis analysis;
        try {
            analysis = engine.analyze(frame);
            consecutiveEngineErrors = 0;
        } catch (LivenessEngineException | RuntimeException e) {
            LOG.log(Level.FINE, "Liveness engine failed on frame " + frame.getSequence(), e);
            if (++consecutiveEngineErrors >= MAX_CONSECUTIVE_ENGINE_ERRORS) {
                consecutiveEngineErrors = 0;
                session.onSystemError(FailureReason.ENGINE_ERROR, String.valueOf(e.getMessage()), clock.getAsLong());
                afterStep();
            }
            return;
        }
        metrics.frameProcessed(System.nanoTime() - started);
        session.onAnalysis(analysis, frame, clock.getAsLong());
        afterStep();
    }

    private void onStreamLost(FailureReason reason, String detail) {
        submit(() -> {
            streaming = false;
            device.stopStream();
            session.onSystemError(reason, detail, clock.getAsLong());
            afterStep();
        });
    }

    /** Releases the device and threads once the session is finished. */
    private void afterStep() {
        if (released || !session.state().isTerminal()) {
            return;
        }
        released = true;
        ticker.shutdownNow();
        if (streaming) {
            device.stopStream();
            streaming = false;
        }
        device.close();
        worker.shutdown();
        result.complete(finished.get());
    }

    private void submit(Runnable task) {
        try {
            worker.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.SEVERE, "Unexpected error in liveness session " + session.getSessionId(), e);
                    session.onSystemError(FailureReason.ENGINE_ERROR, "internal error", clock.getAsLong());
                    afterStep();
                }
            });
        } catch (RejectedExecutionException e) {
            // Session already finished; late frames, ticks and retries are ignored.
        }
    }

    private static FailureReason reasonFor(DeviceException e) {
        switch (e.getKind()) {
            case UNAVAILABLE:
                return FailureReason.DEVICE_UNAVAILABLE;
            case CAMERA_UNAVAILABLE:
                return FailureReason.CAMERA_UNAVAILABLE;
            case CONNECTION_FAILED:
                return FailureReason.DEVICE_CONNECTION_FAILED;
            default:
                return FailureReason.DEVICE_DISCONNECTED;
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private final class DeviceFrames implements FrameListener {
        @Override
        public void onFrame(FaceFrame frame) {
            try {
                listener.onPreviewFrame(frame);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Preview listener failed", e);
            }
            if (pending.getAndSet(frame) != null) {
                metrics.frameDropped();
            }
            if (drainScheduled.compareAndSet(false, true)) {
                submit(LivenessHandle.this::drain);
            }
        }

        @Override
        public void onStreamError(DeviceException error) {
            onStreamLost(reasonFor(error), error.getMessage());
        }

        @Override
        public void onStreamEnded() {
            onStreamLost(FailureReason.DEVICE_DISCONNECTED, "device stream ended");
        }
    }
}
