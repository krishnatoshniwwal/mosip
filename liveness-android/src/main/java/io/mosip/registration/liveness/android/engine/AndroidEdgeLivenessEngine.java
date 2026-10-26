package io.mosip.registration.liveness.android.engine;

import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.LivenessEngineException;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Edge-optimized {@link LivenessEngine} wrapper for Android registration clients.
 *
 * <p>Provides mobile-specific performance enhancements:
 * <ul>
 *   <li><b>Inference rate throttling:</b> limits neural net executions to a target rate
 *       (e.g. 10–12 fps) on constrained ARM chipsets to prevent thermal throttling and battery drain.</li>
 *   <li><b>Latest-result caching:</b> returns smoothed or cached observation when frames arrive faster
 *       than mobile inference latency.</li>
 *   <li><b>Memory safety:</b> guards against mobile OutOfMemory faults on lower-end devices.</li>
 * </ul>
 */
public class AndroidEdgeLivenessEngine implements LivenessEngine {

    private final LivenessEngine delegate;
    private final EngineInfo edgeInfo;
    private final long minFrameIntervalMs;
    private final AtomicLong lastInferenceTimeMs = new AtomicLong();
    private volatile FrameAnalysis cachedAnalysis;

    public AndroidEdgeLivenessEngine(LivenessEngine delegate) {
        this(delegate, 10); // default 10 fps on mobile
    }

    public AndroidEdgeLivenessEngine(LivenessEngine delegate, int targetMaxFps) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        int fps = Math.max(1, Math.min(30, targetMaxFps));
        this.minFrameIntervalMs = 1000L / fps;

        EngineInfo di = delegate.info();
        this.edgeInfo = new EngineInfo(
                di.getName() + " (Android Edge)",
                di.getVersion(),
                di.getModelVersion() + "-mobile",
                di.getSupportedChallenges(),
                di.isPadSupported()
        );
    }

    @Override
    public EngineInfo info() {
        return edgeInfo;
    }

    @Override
    public FrameAnalysis analyze(FaceFrame frame) throws LivenessEngineException {
        long now = System.currentTimeMillis();
        long last = lastInferenceTimeMs.get();

        // Throttle inference on mobile edge if called too frequently
        if (cachedAnalysis != null && (now - last) < minFrameIntervalMs) {
            return cachedAnalysis;
        }

        try {
            FrameAnalysis analysis = delegate.analyze(frame);
            cachedAnalysis = analysis;
            lastInferenceTimeMs.set(now);
            return analysis;
        } catch (OutOfMemoryError oom) {
            cachedAnalysis = null;
            System.gc();
            throw new LivenessEngineException("Low memory on Android edge device during inference", oom);
        }
    }

    @Override
    public void close() {
        cachedAnalysis = null;
        delegate.close();
    }
}
