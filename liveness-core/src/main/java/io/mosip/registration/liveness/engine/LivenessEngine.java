package io.mosip.registration.liveness.engine;

import io.mosip.registration.liveness.device.FaceFrame;

/**
 * Liveness/PAD processing interface. An implementation wraps one on-device technology (an ONNX or
 * TFLite model, a vendor SDK, an L1 device's built-in liveness) and turns frames into measurements.
 * All decisions (thresholds, challenges, retries) are made by the session, not by the engine.
 *
 * <p>Engines must run fully offline. One session calls {@link #analyze} from a single worker
 * thread; an engine shared between sessions must be thread-safe.
 */
public interface LivenessEngine extends AutoCloseable {

    EngineInfo info();

    FrameAnalysis analyze(FaceFrame frame) throws LivenessEngineException;

    @Override
    default void close() {
    }
}
