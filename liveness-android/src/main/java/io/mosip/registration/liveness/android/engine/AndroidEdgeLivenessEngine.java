package io.mosip.registration.liveness.android.engine;

import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.LivenessEngineException;

import java.util.Objects;

/**
 * {@link LivenessEngine} wrapper for Android registration clients: labels the engine as the mobile build in
 * every session's audit, and turns an out-of-memory error on a low-end device into an engine error (so the
 * session fails the attempt instead of the app crashing).
 *
 * <p>Every call analyses the frame it is given. Throttling for battery and heat happens before analysis, in
 * {@link io.mosip.registration.liveness.android.camera.CameraXFaceCaptureDevice} (frames above its analysis
 * rate are dropped); the session's latest-frame-wins handling also drops frames when analysis is slower than
 * the camera. Returning an earlier frame's result for a new frame is never safe: the new frame would get the
 * old frame's PAD verdict, and the captured image could differ from the one that passed.
 */
public class AndroidEdgeLivenessEngine implements LivenessEngine {

    private final LivenessEngine delegate;
    private final EngineInfo edgeInfo;

    public AndroidEdgeLivenessEngine(LivenessEngine delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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
        try {
            return delegate.analyze(frame);
        } catch (OutOfMemoryError oom) {
            throw new LivenessEngineException("Low memory on Android edge device during inference", oom);
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
