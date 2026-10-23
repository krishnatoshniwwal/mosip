package io.mosip.registration.liveness.mock;

import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.LivenessEngineException;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Engine for frames from {@link MockFaceDevice}: reports the simulated subject's measurements as
 * if a model had computed them. It cannot analyse real camera pixels; it stands in for a real
 * engine (ONNX/TFLite model or vendor SDK) behind the same interface.
 */
public final class SimulatedLivenessEngine implements LivenessEngine {

    private static final EngineInfo INFO = new EngineInfo("MOSIP simulated liveness engine", "0.1.0", "simulation",
            EnumSet.allOf(ChallengeType.class), true);

    private final AtomicInteger failures = new AtomicInteger();

    @Override
    public EngineInfo info() {
        return INFO;
    }

    /** The next {@code count} calls to {@link #analyze} throw, to exercise engine error handling. */
    public void failNext(int count) {
        failures.set(count);
    }

    @Override
    public FrameAnalysis analyze(FaceFrame frame) throws LivenessEngineException {
        if (failures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new LivenessEngineException("Simulated engine failure");
        }
        Object truth = frame.getMetadata().get(SimulatedFrameTruth.METADATA_KEY);
        if (!(truth instanceof SimulatedFrameTruth)) {
            throw new LivenessEngineException("SimulatedLivenessEngine only analyses frames from MockFaceDevice");
        }
        SimulatedFrameTruth t = (SimulatedFrameTruth) truth;
        return new FrameAnalysis(t.getFaces(), t.getLivenessScore(), t.getPad());
    }
}
