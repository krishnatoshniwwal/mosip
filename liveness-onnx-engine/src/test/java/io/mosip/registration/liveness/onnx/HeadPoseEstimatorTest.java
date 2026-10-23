package io.mosip.registration.liveness.onnx;

import org.junit.jupiter.api.Test;
import org.opencv.core.Point;
import org.opencv.core.Rect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic landmarks in unmirrored image coordinates (x right, y down), eyes 60 px apart. */
class HeadPoseEstimatorTest {

    private static final double EYE_DISTANCE = 60;

    @Test
    void frontalFaceIsStraightAhead() {
        float[] pose = HeadPoseEstimator.estimate(face(0, 0, 0));
        assertEquals(0f, pose[0], 0.5f, "yaw");
        assertEquals(0f, pose[1], 0.5f, "pitch");
        assertEquals(0f, pose[2], 0.5f, "roll");
    }

    @Test
    void noseTowardsImageRightIsATurnToTheSubjectsLeft() {
        float[] left = HeadPoseEstimator.estimate(face(25, 0, 0));
        float[] right = HeadPoseEstimator.estimate(face(-25, 0, 0));
        assertEquals(25f, left[0], 0.5f);
        assertEquals(-25f, right[0], 0.5f);
    }

    @Test
    void slidingTheWholeFaceSidewaysIsNotATurn() {
        float[] centred = HeadPoseEstimator.estimate(face(0, 0, 0));
        float[] shifted = HeadPoseEstimator.estimate(shift(face(0, 0, 0), 200, 0));
        assertEquals(centred[0], shifted[0], 0.01f, "a photo moved across the frame keeps the same yaw");
    }

    @Test
    void tiltingTheHeadDoesNotLookLikeATurn() {
        float[] pose = HeadPoseEstimator.estimate(face(0, 0, 15));
        assertEquals(0f, pose[0], 0.5f, "yaw");
        assertEquals(15f, pose[2], 0.5f, "roll");
    }

    @Test
    void noseCloserToTheEyesIsLookingUp() {
        assertTrue(HeadPoseEstimator.estimate(face(0, 15, 0))[1] > 10f);
        assertTrue(HeadPoseEstimator.estimate(face(0, -15, 0))[1] < -10f);
    }

    @Test
    void degenerateLandmarksGiveNoPose() {
        Point p = new Point(100, 100);
        DetectedFace collapsed = new DetectedFace(new Rect(90, 90, 20, 20), p, p, p, p, p, 0.9f);
        assertTrue(Float.isNaN(HeadPoseEstimator.estimate(collapsed)[0]));
    }

    /**
     * Landmarks for a face with the given yaw (degrees, positive = subject's left), pitch offset
     * (degrees, positive = up) and roll (degrees), built with the estimator's own face model.
     */
    private static DetectedFace face(double yawDeg, double pitchDeg, double rollDeg) {
        double mouthDrop = 60;
        double noseX = HeadPoseEstimator.NOSE_DEPTH * EYE_DISTANCE * Math.tan(Math.toRadians(yawDeg));
        double noseY = (HeadPoseEstimator.PITCH_NEUTRAL - pitchDeg / HeadPoseEstimator.PITCH_GAIN) * mouthDrop;
        double[][] pts = {
                {-EYE_DISTANCE / 2, 0}, {EYE_DISTANCE / 2, 0}, {noseX, noseY}, {-20, mouthDrop}, {20, mouthDrop}};
        double r = Math.toRadians(rollDeg);
        Point[] out = new Point[pts.length];
        for (int i = 0; i < pts.length; i++) {
            double x = pts[i][0] * Math.cos(r) - pts[i][1] * Math.sin(r);
            double y = pts[i][0] * Math.sin(r) + pts[i][1] * Math.cos(r);
            out[i] = new Point(320 + x, 200 + y);
        }
        return new DetectedFace(new Rect(270, 160, 100, 120), out[1], out[0], out[2], out[4], out[3], 0.95f);
    }

    private static DetectedFace shift(DetectedFace f, double dx, double dy) {
        Rect b = f.getBox();
        return new DetectedFace(new Rect((int) (b.x + dx), (int) (b.y + dy), b.width, b.height),
                move(f.getEyeA(), dx, dy), move(f.getEyeB(), dx, dy), move(f.getNoseTip(), dx, dy),
                move(f.getMouthA(), dx, dy), move(f.getMouthB(), dx, dy), f.getScore());
    }

    private static Point move(Point p, double dx, double dy) {
        return new Point(p.x + dx, p.y + dy);
    }
}
