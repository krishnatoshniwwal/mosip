package io.mosip.registration.liveness.onnx;

import org.junit.jupiter.api.Test;
import org.opencv.core.Point;
import org.opencv.core.Rect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry on synthetic mesh points (crop pixels, y down); the model run itself is covered by the live check. */
class FaceMeshAnalyzerTest {

    @Test
    void eyelidAspectRatioSeparatesOpenFromClosed() {
        double open = FaceMeshAnalyzer.lidAspectRatio(irisContour(32, 9.6));
        double closed = FaceMeshAnalyzer.lidAspectRatio(irisContour(32, 1.0));
        assertEquals(0.30, open, 1e-6);
        assertEquals(1.0 / 32, closed, 1e-6);
        assertEquals(1f, FaceMeshAnalyzer.openness(open), 1e-6);
        assertEquals(0f, FaceMeshAnalyzer.openness(closed), 1e-6);
        assertTrue(Double.isNaN(FaceMeshAnalyzer.lidAspectRatio(new float[71 * 3])), "collapsed contour");
    }

    @Test
    void opennessIsMonotonicAndClamped() {
        assertEquals(0f, FaceMeshAnalyzer.openness(0.0));
        assertEquals(1f, FaceMeshAnalyzer.openness(0.6));
        assertTrue(FaceMeshAnalyzer.openness(0.20) > FaceMeshAnalyzer.openness(0.15));
        assertEquals(0.5f, FaceMeshAnalyzer.openness(0.15), 1e-6);
        assertTrue(Float.isNaN(FaceMeshAnalyzer.openness(Double.NaN)));
    }

    @Test
    void eyeCropIsCentredBetweenTheCornersAndLevelledAlongThem() {
        Point[] c = FaceMeshAnalyzer.eyeRoiCorners(new Point(100, 100), new Point(140, 100));
        assertEquals(120 - 46, c[0].x, 1e-6, "side 2.3 x 40 = 92");
        assertEquals(100 - 46, c[0].y, 1e-6);
        assertEquals(120 + 46, c[1].x, 1e-6);
        Point[] t = FaceMeshAnalyzer.eyeRoiCorners(new Point(100, 100), new Point(140, 110));
        assertEquals(Math.toDegrees(Math.atan2(10, 40)), Math.toDegrees(Math.atan2(t[1].y - t[0].y, t[1].x - t[0].x)), 1e-6);
    }

    @Test
    void meshPointsMapBackToTheSourceImage() {
        Point[] roi = {new Point(50, 60), new Point(242, 60), new Point(50, 252)};
        float[] pts = new float[468 * 3];
        pts[3 * 7] = 96;
        pts[3 * 7 + 1] = 48;
        Point p = FaceMeshAnalyzer.toFrame(roi, pts, 7);
        assertEquals(146, p.x, 1e-6);
        assertEquals(108, p.y, 1e-6);
    }

    @Test
    void widerMouthWithRaisedCornersScoresAsASmile() {
        float[] rest = face(0.55, 0.0);
        float[] smile = face(0.68, 0.06);
        float restScore = FaceMeshAnalyzer.smileScore(FaceMeshAnalyzer.mouthWidthRatio(rest), FaceMeshAnalyzer.cornerLift(rest));
        float smileScore = FaceMeshAnalyzer.smileScore(FaceMeshAnalyzer.mouthWidthRatio(smile), FaceMeshAnalyzer.cornerLift(smile));
        assertEquals(0.55, FaceMeshAnalyzer.mouthWidthRatio(rest), 1e-6);
        assertEquals(0.06, FaceMeshAnalyzer.cornerLift(smile), 1e-6);
        assertTrue(restScore < 0.3f, "rest " + restScore);
        assertTrue(smileScore >= 0.6f, "smile " + smileScore + " must reach the smile detector's target");
        assertTrue(Float.isNaN(FaceMeshAnalyzer.smileScore(Double.NaN, 0)));
    }

    @Test
    void cropIsTheFaceBoxEnlargedIntoASquareAndLevelledByTheEyes() {
        DetectedFace level = new DetectedFace(new Rect(100, 100, 80, 100),
                new Point(120, 130), new Point(160, 130), new Point(140, 150), new Point(125, 175), new Point(155, 175), 0.9f);
        Point[] c = FaceMeshAnalyzer.roiCorners(level);
        assertEquals(140 - 75, c[0].x, 1e-6, "side 1.5 x 100 = 150, centred on the box");
        assertEquals(150 - 75, c[0].y, 1e-6);
        assertEquals(140 + 75, c[1].x, 1e-6);
        assertEquals(150 + 75, c[2].y, 1e-6);

        DetectedFace tilted = new DetectedFace(new Rect(100, 100, 80, 100),
                new Point(120, 130), new Point(160, 130 + 40 * Math.tan(Math.toRadians(20))), new Point(140, 150),
                new Point(125, 175), new Point(155, 175), 0.9f);
        Point[] t = FaceMeshAnalyzer.roiCorners(tilted);
        assertEquals(20, Math.toDegrees(Math.atan2(t[1].y - t[0].y, t[1].x - t[0].x)), 1e-6, "top edge follows the eye line");
        assertEquals(150, Math.hypot(t[1].x - t[0].x, t[1].y - t[0].y), 1e-6);
    }

    /** Iris eye contour: corners 0 and 8 {@code w} apart, lid pairs (11,3) (12,4) (13,5) {@code h} apart. */
    private static float[] irisContour(double w, double h) {
        float[] p = new float[71 * 3];
        set(p, 0, 16, 32);
        set(p, 8, 16 + w, 32);
        for (int i = 0; i < 3; i++) {
            double x = 16 + w * (i + 1) / 4;
            set(p, 11 + i, x, 32 - h / 2);
            set(p, 3 + i, x, 32 + h / 2);
        }
        return p;
    }

    /** Outer eye corners 100 px apart; mouth of the given width ratio with corners raised by lift x 100 px. */
    private static float[] face(double widthRatio, double lift) {
        float[] p = new float[468 * 3];
        set(p, FaceMeshAnalyzer.RIGHT_EYE_OUTER, 46, 80);
        set(p, FaceMeshAnalyzer.LEFT_EYE_OUTER, 146, 80);
        double half = widthRatio * 100 / 2;
        set(p, FaceMeshAnalyzer.MOUTH_RIGHT, 96 - half, 140 - lift * 100);
        set(p, FaceMeshAnalyzer.MOUTH_LEFT, 96 + half, 140 - lift * 100);
        set(p, FaceMeshAnalyzer.LIP_UPPER, 96, 138);
        set(p, FaceMeshAnalyzer.LIP_LOWER, 96, 142);
        return p;
    }

    private static void set(float[] p, int i, double x, double y) {
        p[3 * i] = (float) x;
        p[3 * i + 1] = (float) y;
    }
}
