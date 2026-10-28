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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidEdgeLivenessEngineTest {

    private static final EngineInfo BASE = new EngineInfo("BaseModel", "1.0", "m1",
            EnumSet.allOf(ChallengeType.class), true);

    /**
     * Regression: the wrapper used to return the previous frame's analysis for frames arriving within its
     * interval, so a spoof frame right after a genuine one was never checked.
     */
    @Test
    void everyFrameGetsItsOwnAnalysis() throws Exception {
        AtomicInteger inferences = new AtomicInteger();
        LivenessEngine base = new LivenessEngine() {
            @Override
            public EngineInfo info() {
                return BASE;
            }

            @Override
            public FrameAnalysis analyze(FaceFrame frame) {
                inferences.incrementAndGet();
                // Odd frames are attacks: each verdict must belong to its own frame.
                return new FrameAnalysis(Collections.emptyList(), 90f, frame.getSequence() % 2 == 1
                        ? PadResult.attack(null, 10f) : PadResult.bonaFide(90f));
            }
        };
        AndroidEdgeLivenessEngine edge = new AndroidEdgeLivenessEngine(base);
        assertTrue(edge.info().getName().contains("Android Edge"));
        assertTrue(edge.info().getModelVersion().endsWith("-mobile"));

        for (int i = 0; i < 10; i++) {
            FrameAnalysis result = edge.analyze(frame(i, 1000L));
            assertEquals(i % 2 == 1, result.getPad().isAttackDetected(), "frame " + i + " got another frame's verdict");
        }
        assertEquals(10, inferences.get(), "no frame may be answered from a cache");
        edge.close();
    }

    @Test
    void outOfMemoryBecomesAnEngineError() {
        LivenessEngine base = new LivenessEngine() {
            @Override
            public EngineInfo info() {
                return BASE;
            }

            @Override
            public FrameAnalysis analyze(FaceFrame frame) {
                throw new OutOfMemoryError("simulated");
            }
        };
        LivenessEngineException e = assertThrows(LivenessEngineException.class,
                () -> new AndroidEdgeLivenessEngine(base).analyze(frame(0, 0L)));
        assertFalse(e.getMessage().isEmpty());
    }

    private static FaceFrame frame(long sequence, long timestampMs) {
        return new FaceFrame("DEV", sequence, timestampMs, 32, 24, PixelFormat.NV21,
                new byte[32 * 24 * 3 / 2], Collections.emptyMap());
    }
}
