package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.config.CaptureArea;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.PadResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The evaluation scores only the frames a liveness session would judge. */
class PadEvaluationToolTest {

    private static final CaptureArea AREA = LivenessPolicy.builder().build().getCaptureArea();
    private static final FaceObservation IN_OVAL = face(0.5f);
    private static final FaceObservation BESIDE_OVAL = face(0.15f);

    @Test
    void oneFaceInsideTheOvalCounts() {
        assertTrue(PadEvaluationTool.counts(frame(IN_OVAL), AREA));
    }

    @Test
    void aFaceBesideTheOvalDoesNotCount() {
        // Found in the team's recordings: a person behind the attacker, alone in a few frames, was scored as a
        // "live" attack. The app only asks them to move into the oval.
        assertFalse(PadEvaluationTool.counts(frame(BESIDE_OVAL), AREA));
    }

    @Test
    void twoFacesDoNotCount() {
        FrameAnalysis two = new FrameAnalysis(Arrays.asList(IN_OVAL, BESIDE_OVAL), 80f, PadResult.bonaFide(80f));
        assertFalse(PadEvaluationTool.counts(two, AREA));
    }

    @Test
    void withoutTheOvalAnySingleFaceCounts() {
        assertTrue(PadEvaluationTool.counts(frame(BESIDE_OVAL), null));
    }

    @Test
    void aFrameWithoutAPadScoreDoesNotCount() {
        FrameAnalysis unscored = new FrameAnalysis(Collections.singletonList(IN_OVAL), Float.NaN, PadResult.notEvaluated());
        assertFalse(PadEvaluationTool.counts(unscored, AREA));
    }

    private static FrameAnalysis frame(FaceObservation face) {
        return new FrameAnalysis(Collections.singletonList(face), 80f, PadResult.bonaFide(80f));
    }

    /** A face the size the oval expects, centred at {@code x}. */
    private static FaceObservation face(float x) {
        return FaceObservation.builder().center(x, 0.48f).size(0.3f, 0.42f).quality(80).eyes(0.9f)
                .pose(0f, 0f, 0f).build();
    }
}
