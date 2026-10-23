package io.mosip.registration.liveness.onnx;

import org.opencv.core.Point;

/**
 * Head pose from the five YuNet landmarks.
 *
 * <p>The landmarks are first rotated so the eyes are level (this angle is the roll). The nose tip
 * sticks out in front of the eye/mouth plane, so turning the head moves it sideways relative to the
 * midpoint of the eyes, and tilting the head moves it up or down between the eyes and the mouth.
 * Unlike the face's position in the frame, this cannot be faked by sliding a photo sideways.
 *
 * <p>Results follow the {@link io.mosip.registration.liveness.engine.FaceObservation} convention
 * for an unmirrored camera image: positive yaw = subject turned to their left, positive pitch =
 * subject looks up. The depth and neutral constants are typical-face values, accurate to a few
 * degrees; challenges are measured relative to each person's own baseline, so per-person offsets
 * cancel out. Calibrate against recorded sessions before relying on absolute angles.
 */
public final class HeadPoseEstimator {

    /** Nose-tip depth in front of the eye plane, as a fraction of the distance between the eyes. */
    static final double NOSE_DEPTH = 0.6;
    /** Where the nose tip sits between eye line (0) and mouth line (1) when looking straight ahead. */
    static final double PITCH_NEUTRAL = 0.55;
    /** Degrees of pitch per unit change of that fraction. */
    static final double PITCH_GAIN = 60.0;

    private HeadPoseEstimator() {
    }

    /** @return {yaw, pitch, roll} in degrees, or NaNs if the landmarks are degenerate */
    public static float[] estimate(DetectedFace face) {
        Point left = face.getEyeA().x <= face.getEyeB().x ? face.getEyeA() : face.getEyeB();
        Point right = left == face.getEyeA() ? face.getEyeB() : face.getEyeA();
        double eyeDx = right.x - left.x;
        double eyeDy = right.y - left.y;
        double eyeDistance = Math.hypot(eyeDx, eyeDy);
        if (eyeDistance < 2) {
            return new float[] {Float.NaN, Float.NaN, Float.NaN};
        }
        double rollRad = Math.atan2(eyeDy, eyeDx);
        double cos = Math.cos(-rollRad);
        double sin = Math.sin(-rollRad);
        double eyeMidX = (left.x + right.x) / 2;
        double eyeMidY = (left.y + right.y) / 2;

        // Nose and mouth relative to the eye midpoint, in the eye-aligned frame.
        double noseDx = face.getNoseTip().x - eyeMidX;
        double noseDy = face.getNoseTip().y - eyeMidY;
        double noseX = noseDx * cos - noseDy * sin;
        double noseY = noseDx * sin + noseDy * cos;
        double mouthDx = (face.getMouthA().x + face.getMouthB().x) / 2 - eyeMidX;
        double mouthDy = (face.getMouthA().y + face.getMouthB().y) / 2 - eyeMidY;
        double mouthY = mouthDx * sin + mouthDy * cos;

        double yaw = Math.toDegrees(Math.atan((noseX / eyeDistance) / NOSE_DEPTH));
        double pitch = mouthY > 1 ? (PITCH_NEUTRAL - noseY / mouthY) * PITCH_GAIN : Double.NaN;
        // Image y grows downwards, so a positive angle here means the subject's head tilts to their left.
        double roll = Math.toDegrees(rollRad);
        return new float[] {(float) yaw, (float) pitch, (float) roll};
    }
}
