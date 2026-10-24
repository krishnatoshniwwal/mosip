package io.mosip.registration.liveness.onnx;

import io.mosip.registration.liveness.engine.AttackType;
import io.mosip.registration.liveness.engine.PadIndicator;
import io.mosip.registration.liveness.engine.PadResult;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import static io.mosip.registration.liveness.onnx.SyntheticFace.FACE_BOX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks each replay cue on synthetic images. This verifies the detector logic, not real-world
 * accuracy, which needs calibration on recorded bona fide and attack sessions.
 */
class ReplayAttackDetectorTest {

    private final ReplayAttackDetector detector = new ReplayAttackDetector();

    @Test
    void naturallyLitFaceRaisesNoCue() {
        Mat img = SyntheticFace.face(true, 1);
        ReplayAttackDetector.Assessment a = detector.analyze(img, FACE_BOX);

        assertTrue(a.indicators.isEmpty(), a.toString());
        assertFalse(a.isAttack(), a.toString());
        assertTrue(a.suspicion < 0.1, a.toString());
    }

    @Test
    void screenPixelGridInterferenceIsDetectedAsMoire() {
        Mat img = SyntheticFace.face(true, 2);
        SyntheticFace.addMoire(img, 14);
        ReplayAttackDetector.Assessment a = detector.analyze(img, FACE_BOX);

        assertTrue(a.indicators.contains(PadIndicator.MOIRE), a.toString());
        assertEquals(1.0, a.moire, 1e-9, a.toString());
    }

    @Test
    void flatEmittedLightIsDetectedAsUniformLighting() {
        Mat flat = SyntheticFace.face(false, 3);
        ReplayAttackDetector.Assessment a = detector.analyze(flat, FACE_BOX);

        assertTrue(a.indicators.contains(PadIndicator.UNIFORM_LIGHTING), a.toString());
        assertFalse(a.isAttack(), "uniform lighting alone must never reject a face: " + a);
    }

    @Test
    void moireOnFlatLightingIsAnAttack() {
        Mat img = SyntheticFace.face(false, 4);
        SyntheticFace.addMoire(img, 14);
        ReplayAttackDetector.Assessment a = detector.analyze(img, FACE_BOX);

        assertTrue(a.isAttack(), a.toString());
    }

    @Test
    void phoneBezelAroundTheFaceIsAnAttack() {
        Mat img = SyntheticFace.face(true, 5);
        SyntheticFace.drawPhoneBezel(img);
        ReplayAttackDetector.Assessment a = detector.analyze(img, FACE_BOX);

        assertTrue(a.indicators.contains(PadIndicator.BEZEL_EDGES), a.toString());
        assertEquals(1.0, a.bezel, 1e-9, a.toString());
        assertTrue(a.isAttack(), a.toString());
    }

    @Test
    void replayCuesTurnABonaFideModelVerdictIntoAScreenAttack() {
        Mat img = SyntheticFace.face(false, 6);
        SyntheticFace.addMoire(img, 14);
        PadResult combined = ReplayAttackDetector.combine(PadResult.bonaFide(92f), detector.analyze(img, FACE_BOX));

        assertTrue(combined.isAttackDetected());
        assertEquals(AttackType.SCREEN_DISPLAY, combined.getAttackType());
        assertTrue(combined.getBonaFideScore() < 92f * 0.75f, "score " + combined.getBonaFideScore());
        assertTrue(combined.getIndicators().contains(PadIndicator.MOIRE));
    }

    @Test
    void noCuesLeaveTheModelVerdictAndScoreUnchanged() {
        Mat img = SyntheticFace.face(true, 7);
        PadResult combined = ReplayAttackDetector.combine(PadResult.bonaFide(88f), detector.analyze(img, FACE_BOX));

        assertFalse(combined.isAttackDetected());
        assertTrue(combined.getBonaFideScore() > 88f * 0.95f, "score " + combined.getBonaFideScore());
    }

    @Test
    void modelAttackStaysAnAttackAndIsTaggedAsModel() {
        Mat img = SyntheticFace.face(true, 8);
        PadResult combined = ReplayAttackDetector.combine(PadResult.attack(AttackType.UNKNOWN, 12f),
                detector.analyze(img, FACE_BOX));

        assertTrue(combined.isAttackDetected());
        assertTrue(combined.getIndicators().contains(PadIndicator.MODEL));
    }

    @Test
    void jpegBlockHarmonicsAreNotMistakenForMoire() {
        double n = 128;
        float[] mag = new float[(int) (n * n)];
        java.util.Random random = new java.util.Random(9);
        for (int i = 0; i < mag.length; i++) {
            mag[i] = (float) Math.abs(random.nextGaussian());
        }
        // Strong peak exactly on the 8-pixel block grid of a 160-pixel-wide crop: bins at multiples of 20.
        mag[20 * 128 + 20] = 500f;
        double withGridExcluded = ReplayAttackDetector.whitenedPeak(mag, 128, 20, 20);
        double withoutExclusion = ReplayAttackDetector.whitenedPeak(mag, 128, 0, 0);

        assertTrue(withoutExclusion > 100, String.valueOf(withoutExclusion));
        assertTrue(withGridExcluded < 9, String.valueOf(withGridExcluded));
    }
}
