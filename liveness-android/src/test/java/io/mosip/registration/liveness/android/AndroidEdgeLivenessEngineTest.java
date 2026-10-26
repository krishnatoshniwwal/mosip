package io.mosip.registration.liveness.android;

import io.mosip.registration.liveness.android.engine.AndroidEdgeLivenessEngine;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.PixelFormat;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.LivenessEngineException;
import io.mosip.registration.liveness.engine.PadResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidEdgeLivenessEngineTest {

    @Test
    void edgeEngineThrottlesRapidFrames() throws Exception {
        AtomicInteger inferenceCount = new AtomicInteger();
        EngineInfo baseInfo = new EngineInfo("BaseModel", "1.0", "m1", EnumSet.allOf(ChallengeType.class), true);

        LivenessEngine baseEngine = new LivenessEngine() {
            @Override
            public EngineInfo info() {
                return baseInfo;
            }

            @Override
            public FrameAnalysis analyze(FaceFrame frame) {
                inferenceCount.incrementAndGet();
                return new FrameAnalysis(Collections.emptyList(), 90f, PadResult.bonaFide(90f));
            }
        };

        // Target max 5 fps = min 200ms interval
        AndroidEdgeLivenessEngine edgeEngine = new AndroidEdgeLivenessEngine(baseEngine, 5);
        assertTrue(edgeEngine.info().getName().contains("Android Edge"));
        assertTrue(edgeEngine.info().getModelVersion().contains("mobile"));

        FaceFrame frame = new FaceFrame("DEV", 1, 1000L, 32, 24, PixelFormat.NV21,
                new byte[32 * 24 * 3 / 2], Collections.emptyMap());

        // Call 10 times consecutively with zero delay
        for (int i = 0; i < 10; i++) {
            FrameAnalysis res = edgeEngine.analyze(frame);
            assertNotNull(res);
        }

        // Only 1 actual inference should have been performed; subsequent 9 were returned from cache
        assertEquals(1, inferenceCount.get(), "Subsequent rapid frames must be throttled to preserve mobile resources");

        edgeEngine.close();
    }
}
