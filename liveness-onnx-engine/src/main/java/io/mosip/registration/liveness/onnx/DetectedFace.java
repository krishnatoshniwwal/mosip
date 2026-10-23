package io.mosip.registration.liveness.onnx;

import org.opencv.core.Point;
import org.opencv.core.Rect;

/**
 * A face found by {@link FaceDetector}: bounding box, the five YuNet landmarks and the detection
 * score, all in frame pixel coordinates of the unmirrored camera image.
 */
public final class DetectedFace {
    private final Rect box;
    private final Point eyeA;
    private final Point eyeB;
    private final Point noseTip;
    private final Point mouthA;
    private final Point mouthB;
    private final float score;

    public DetectedFace(Rect box, Point eyeA, Point eyeB, Point noseTip, Point mouthA, Point mouthB, float score) {
        this.box = box;
        this.eyeA = eyeA;
        this.eyeB = eyeB;
        this.noseTip = noseTip;
        this.mouthA = mouthA;
        this.mouthB = mouthB;
        this.score = score;
    }

    public Rect getBox() { return box; }
    /** One eye centre; with {@link #getEyeB()} in no guaranteed left/right order. */
    public Point getEyeA() { return eyeA; }
    public Point getEyeB() { return eyeB; }
    public Point getNoseTip() { return noseTip; }
    /** One mouth corner; with {@link #getMouthB()} in no guaranteed left/right order. */
    public Point getMouthA() { return mouthA; }
    public Point getMouthB() { return mouthB; }
    public float getScore() { return score; }
}
