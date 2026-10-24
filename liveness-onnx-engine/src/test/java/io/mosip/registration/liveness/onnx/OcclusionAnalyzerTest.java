package io.mosip.registration.liveness.onnx;

import io.mosip.registration.liveness.engine.FaceRegion;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the occlusion logic on synthetic faces; real-world accuracy needs calibration on captures. */
class OcclusionAnalyzerTest {

    private final OcclusionAnalyzer analyzer = new OcclusionAnalyzer();

    @Test
    void uncoveredFaceIsNotOccluded() {
        assertTrue(analyzer.analyze(SyntheticFace.face(true, 11), SyntheticFace.landmarks()).isEmpty());
        assertTrue(analyzer.analyze(SyntheticFace.face(false, 12), SyntheticFace.landmarks()).isEmpty());
    }

    @Test
    void maskCoversTheLowerFace() {
        Mat img = SyntheticFace.face(true, 13);
        SyntheticFace.drawMask(img);
        SyntheticFace.addNoise(img, 5, 14);

        assertEquals(EnumSet.of(FaceRegion.LOWER_FACE), analyzer.analyze(img, SyntheticFace.landmarks()));
        String readout = analyzer.measure(img, SyntheticFace.landmarks()).measurements;
        assertTrue(readout.contains("lower skin=") && readout.endsWith("=> [LOWER_FACE]"), readout);
    }

    @Test
    void sunglassesCoverTheEyes() {
        Mat img = SyntheticFace.face(true, 15);
        SyntheticFace.drawSunglasses(img);

        assertEquals(EnumSet.of(FaceRegion.EYES), analyzer.analyze(img, SyntheticFace.landmarks()));
    }

    @Test
    void maskAndSunglassesTogether() {
        Mat img = SyntheticFace.face(true, 16);
        SyntheticFace.drawMask(img);
        SyntheticFace.drawSunglasses(img);

        assertEquals(EnumSet.of(FaceRegion.EYES, FaceRegion.LOWER_FACE), analyzer.analyze(img, SyntheticFace.landmarks()));
    }

    @Test
    void beardIsNotMistakenForAMask() {
        Mat img = SyntheticFace.face(true, 17);
        SyntheticFace.drawBeard(img, 18);

        Set<FaceRegion> regions = analyzer.analyze(img, SyntheticFace.landmarks());
        assertTrue(regions.isEmpty(), regions.toString());
    }

    @Test
    void darkerSkinToneIsJudgedAgainstItself() {
        Mat img = SyntheticFace.face(true, 19);
        // Recolour the skin oval to a darker tone; features stay as drawn.
        Mat skinMask = new Mat();
        org.opencv.core.Core.inRange(img, new org.opencv.core.Scalar(70, 95, 120),
                new org.opencv.core.Scalar(150, 185, 240), skinMask);
        img.setTo(new org.opencv.core.Scalar(55, 75, 115), skinMask);
        SyntheticFace.addNoise(img, 5, 20);

        assertTrue(analyzer.analyze(img, SyntheticFace.landmarks()).isEmpty());

        SyntheticFace.drawMask(img);
        assertEquals(EnumSet.of(FaceRegion.LOWER_FACE), analyzer.analyze(img, SyntheticFace.landmarks()));
    }
}
