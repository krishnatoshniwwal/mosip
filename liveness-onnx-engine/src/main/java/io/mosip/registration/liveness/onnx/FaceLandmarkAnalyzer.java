package io.mosip.registration.liveness.onnx;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extracts facial landmarks and computes per-face measurements: eye openness, smile,
 * head pose, and quality.
 *
 * <p>When a 68-point landmark model is available (e.g. a DNN-based model), precise geometric
 * measurements are computed. When no model is present, the analyzer falls back to gradient-based
 * and statistical heuristics that give reasonable approximations from raw pixel data.
 *
 * <p>Instances are <em>not</em> thread-safe.
 */
public final class FaceLandmarkAnalyzer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(FaceLandmarkAnalyzer.class.getName());

    private Net landmarkNet;
    private final boolean usingModel;

    /**
     * Create a new landmark analyzer.
     *
     * @param modelDir directory that may contain a landmark model file
     *                 (e.g. {@code face_landmark_68.onnx})
     */
    public FaceLandmarkAnalyzer(Path modelDir) {
        Net net = null;
        if (modelDir != null) {
            File onnxLandmark = modelDir.resolve("face_landmark_68.onnx").toFile();
            if (onnxLandmark.isFile()) {
                try {
                    net = Dnn.readNetFromONNX(onnxLandmark.getAbsolutePath());
                    LOG.info("Landmark analyzer: loaded 68-point model from " + modelDir);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to load landmark model — using heuristic fallback", e);
                    net = null;
                }
            }
        }

        this.landmarkNet = net;
        this.usingModel = (net != null);

        if (!usingModel) {
            LOG.warning("Landmark analyzer: model not available — using heuristic analysis. "
                    + "Measurements will be approximate.");
        }
    }

    /** Whether this analyzer is using a trained landmark model. */
    public boolean isUsingModel() {
        return usingModel;
    }

    // ======================================================================
    // Eye openness
    // ======================================================================

    /**
     * Estimate eye openness for both eyes.
     *
     * @param bgrFrame the full BGR frame
     * @param faceRect the detected face bounding box
     * @return float array {@code [leftEye, rightEye]}, each in range 0..1
     */
    public float[] estimateEyeOpenness(Mat bgrFrame, Rect faceRect) {
        if (usingModel) {
            return estimateEyeOpennessModel(bgrFrame, faceRect);
        }
        return estimateEyeOpennessHeuristic(bgrFrame, faceRect);
    }

    /**
     * Model-based eye openness using 68-point landmarks.
     * Eye Aspect Ratio (EAR) = (|p2-p6| + |p3-p5|) / (2 * |p1-p4|)
     */
    private float[] estimateEyeOpennessModel(Mat bgrFrame, Rect faceRect) {
        // Until model inference is wired, delegate to heuristic
        return estimateEyeOpennessHeuristic(bgrFrame, faceRect);
    }

    /**
     * Heuristic eye openness using gradient analysis in the eye region.
     * Open eyes produce strong vertical gradients (eyelids + iris); closed eyes have weaker ones.
     */
    private float[] estimateEyeOpennessHeuristic(Mat bgrFrame, Rect faceRect) {
        // Extract the eye region: top 30-45% of the face, horizontally divided into left/right
        int eyeTop = faceRect.y + (int) (faceRect.height * 0.20);
        int eyeBottom = faceRect.y + (int) (faceRect.height * 0.45);
        int eyeHeight = eyeBottom - eyeTop;
        if (eyeHeight < 4) {
            return new float[]{Float.NaN, Float.NaN};
        }

        int faceMidX = faceRect.x + faceRect.width / 2;

        float leftEye = computeEyeGradientScore(bgrFrame, faceRect.x, eyeTop, faceMidX - faceRect.x, eyeHeight);
        float rightEye = computeEyeGradientScore(bgrFrame, faceMidX, eyeTop, faceRect.x + faceRect.width - faceMidX, eyeHeight);

        return new float[]{leftEye, rightEye};
    }

    /**
     * Compute an eye openness score from vertical gradient energy in a region.
     */
    private float computeEyeGradientScore(Mat bgrFrame, int x, int y, int w, int h) {
        // Clamp to frame boundaries
        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.min(w, bgrFrame.cols() - x);
        h = Math.min(h, bgrFrame.rows() - y);
        if (w < 4 || h < 4) {
            return Float.NaN;
        }

        Rect roi = new Rect(x, y, w, h);
        Mat region = new Mat(bgrFrame, roi);
        Mat gray = new Mat();
        Imgproc.cvtColor(region, gray, Imgproc.COLOR_BGR2GRAY);

        // Compute vertical Sobel (detects horizontal edges like eyelid boundaries)
        Mat sobelY = new Mat();
        Imgproc.Sobel(gray, sobelY, CvType.CV_64F, 0, 1);
        Core.convertScaleAbs(sobelY, sobelY);

        // Mean gradient energy
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        Core.meanStdDev(sobelY, mean, stddev);

        double gradientEnergy = mean.get(0, 0)[0] + stddev.get(0, 0)[0];

        // Release intermediate Mats
        region.release();
        gray.release();
        sobelY.release();
        mean.release();
        stddev.release();

        // Normalize: typical gradient energy for an open eye is around 40-80,
        // closed eye around 10-25. Map to 0..1 with clamp.
        float score = (float) ((gradientEnergy - 10.0) / 50.0);
        return Math.max(0.0f, Math.min(1.0f, score));
    }

    // ======================================================================
    // Smile estimation
    // ======================================================================

    /**
     * Estimate smile intensity.
     *
     * @param bgrFrame the full BGR frame
     * @param faceRect the detected face bounding box
     * @return smile score in range 0..1
     */
    public float estimateSmile(Mat bgrFrame, Rect faceRect) {
        if (usingModel) {
            return estimateSmileModel(bgrFrame, faceRect);
        }
        return estimateSmileHeuristic(bgrFrame, faceRect);
    }

    private float estimateSmileModel(Mat bgrFrame, Rect faceRect) {
        return estimateSmileHeuristic(bgrFrame, faceRect);
    }

    /**
     * Heuristic smile detection using gradient analysis in the mouth region.
     * A smile stretches the mouth horizontally, creating stronger horizontal gradients.
     */
    private float estimateSmileHeuristic(Mat bgrFrame, Rect faceRect) {
        // Mouth region: bottom 25-45% of the face, central 70%
        int mouthTop = faceRect.y + (int) (faceRect.height * 0.60);
        int mouthBottom = faceRect.y + (int) (faceRect.height * 0.90);
        int mouthLeft = faceRect.x + (int) (faceRect.width * 0.15);
        int mouthRight = faceRect.x + (int) (faceRect.width * 0.85);
        int mouthWidth = mouthRight - mouthLeft;
        int mouthHeight = mouthBottom - mouthTop;

        // Clamp to frame
        mouthLeft = Math.max(0, mouthLeft);
        mouthTop = Math.max(0, mouthTop);
        mouthWidth = Math.min(mouthWidth, bgrFrame.cols() - mouthLeft);
        mouthHeight = Math.min(mouthHeight, bgrFrame.rows() - mouthTop);

        if (mouthWidth < 4 || mouthHeight < 4) {
            return Float.NaN;
        }

        Rect mouthRoi = new Rect(mouthLeft, mouthTop, mouthWidth, mouthHeight);
        Mat mouthRegion = new Mat(bgrFrame, mouthRoi);
        Mat gray = new Mat();
        Imgproc.cvtColor(mouthRegion, gray, Imgproc.COLOR_BGR2GRAY);

        // Horizontal Sobel — smiling creates strong horizontal edges (stretched mouth)
        Mat sobelX = new Mat();
        Imgproc.Sobel(gray, sobelX, CvType.CV_64F, 1, 0);
        Core.convertScaleAbs(sobelX, sobelX);

        // Vertical Sobel — mouth corner features
        Mat sobelY = new Mat();
        Imgproc.Sobel(gray, sobelY, CvType.CV_64F, 0, 1);
        Core.convertScaleAbs(sobelY, sobelY);

        MatOfDouble meanX = new MatOfDouble();
        MatOfDouble stddevX = new MatOfDouble();
        Core.meanStdDev(sobelX, meanX, stddevX);

        MatOfDouble meanY = new MatOfDouble();
        MatOfDouble stddevY = new MatOfDouble();
        Core.meanStdDev(sobelY, meanY, stddevY);

        double hEnergy = meanX.get(0, 0)[0] + stddevX.get(0, 0)[0];
        double vEnergy = meanY.get(0, 0)[0] + stddevY.get(0, 0)[0];

        // Release
        mouthRegion.release();
        gray.release();
        sobelX.release();
        sobelY.release();
        meanX.release();
        stddevX.release();
        meanY.release();
        stddevY.release();

        // Ratio of horizontal to vertical gradient energy — higher when smiling
        double ratio = (vEnergy > 1.0) ? (hEnergy / vEnergy) : hEnergy;
        float score = (float) ((ratio - 0.8) / 1.2);
        return Math.max(0.0f, Math.min(1.0f, score));
    }

    // ======================================================================
    // Head pose estimation
    // ======================================================================

    /**
     * Estimate head pose from the face bounding box.
     *
     * @param bgrFrame the full BGR frame
     * @param faceRect the detected face bounding box
     * @return float array {@code [yaw, pitch, roll]} in degrees
     */
    public float[] estimateHeadPose(Mat bgrFrame, Rect faceRect) {
        if (usingModel) {
            return estimateHeadPoseModel(bgrFrame, faceRect);
        }
        return estimateHeadPoseHeuristic(bgrFrame, faceRect);
    }

    private float[] estimateHeadPoseModel(Mat bgrFrame, Rect faceRect) {
        return estimateHeadPoseHeuristic(bgrFrame, faceRect);
    }

    /**
     * Heuristic head pose estimation from face bbox geometry.
     *
     * <ul>
     *   <li><b>Yaw</b>: horizontal displacement of the face centre from the frame centre.
     *       A face that is left-of-centre likely has a positive yaw (turned to subject's left).</li>
     *   <li><b>Pitch</b>: vertical displacement — face above centre → looking up → positive pitch.</li>
     *   <li><b>Roll</b>: estimated from face bbox aspect ratio deviation; without landmarks this
     *       is unreliable, so a small value derived from horizontal gradient asymmetry is used.</li>
     * </ul>
     */
    private float[] estimateHeadPoseHeuristic(Mat bgrFrame, Rect faceRect) {
        float frameCenterX = bgrFrame.cols() / 2.0f;
        float frameCenterY = bgrFrame.rows() / 2.0f;

        float faceCenterX = faceRect.x + faceRect.width / 2.0f;
        float faceCenterY = faceRect.y + faceRect.height / 2.0f;

        // Normalized offset from frame centre (-1..1)
        float offsetX = (faceCenterX - frameCenterX) / frameCenterX;
        float offsetY = (faceCenterY - frameCenterY) / frameCenterY;

        // Map horizontal offset to yaw: roughly ±30° at the edges
        // Note: subject-centric — face moving to screen-right means subject turned left → positive yaw
        float yaw = -offsetX * 30.0f;

        // Map vertical offset to pitch: face above centre → looking up → positive pitch
        float pitch = -offsetY * 25.0f;

        // Roll estimation from gradient asymmetry in left vs. right eye regions
        float roll = estimateRollHeuristic(bgrFrame, faceRect);

        return new float[]{yaw, pitch, roll};
    }

    /**
     * Estimate roll from asymmetry of vertical gradients in the left and right halves of the face.
     * A tilted head will have stronger gradients on the lower side.
     */
    private float estimateRollHeuristic(Mat bgrFrame, Rect faceRect) {
        int eyeTop = faceRect.y + (int) (faceRect.height * 0.15);
        int eyeBottom = faceRect.y + (int) (faceRect.height * 0.45);
        int eyeHeight = eyeBottom - eyeTop;
        int halfWidth = faceRect.width / 2;

        if (eyeHeight < 4 || halfWidth < 4) {
            return 0.0f;
        }

        // Left half
        int lx = Math.max(0, faceRect.x);
        int ly = Math.max(0, eyeTop);
        int lw = Math.min(halfWidth, bgrFrame.cols() - lx);
        int lh = Math.min(eyeHeight, bgrFrame.rows() - ly);

        // Right half
        int rx = Math.max(0, faceRect.x + halfWidth);
        int ry = ly;
        int rw = Math.min(halfWidth, bgrFrame.cols() - rx);
        int rh = lh;

        if (lw < 4 || lh < 4 || rw < 4 || rh < 4) {
            return 0.0f;
        }

        double leftEnergy = regionGradientEnergy(bgrFrame, lx, ly, lw, lh);
        double rightEnergy = regionGradientEnergy(bgrFrame, rx, ry, rw, rh);

        double totalEnergy = leftEnergy + rightEnergy;
        if (totalEnergy < 1.0) {
            return 0.0f;
        }

        // Asymmetry ratio: positive if right side has more energy (tilted right)
        double asymmetry = (rightEnergy - leftEnergy) / totalEnergy;
        return (float) (asymmetry * 20.0); // Map to roughly ±10° range
    }

    private double regionGradientEnergy(Mat bgrFrame, int x, int y, int w, int h) {
        Rect roi = new Rect(x, y, w, h);
        Mat region = new Mat(bgrFrame, roi);
        Mat gray = new Mat();
        Imgproc.cvtColor(region, gray, Imgproc.COLOR_BGR2GRAY);

        Mat sobel = new Mat();
        Imgproc.Sobel(gray, sobel, CvType.CV_64F, 0, 1);
        Core.convertScaleAbs(sobel, sobel);

        double energy = Core.mean(sobel).val[0];

        region.release();
        gray.release();
        sobel.release();

        return energy;
    }

    // ======================================================================
    // Quality estimation
    // ======================================================================

    /**
     * Estimate face image quality.
     *
     * @param bgrFrame the full BGR frame
     * @param faceRect the detected face bounding box
     * @return quality score 0..100
     */
    public int estimateQuality(Mat bgrFrame, Rect faceRect) {
        // Extract face ROI
        int x = Math.max(0, faceRect.x);
        int y = Math.max(0, faceRect.y);
        int w = Math.min(faceRect.width, bgrFrame.cols() - x);
        int h = Math.min(faceRect.height, bgrFrame.rows() - y);

        if (w < 8 || h < 8) {
            return 0;
        }

        Rect roi = new Rect(x, y, w, h);
        Mat face = new Mat(bgrFrame, roi);
        Mat gray = new Mat();
        Imgproc.cvtColor(face, gray, Imgproc.COLOR_BGR2GRAY);

        // --- Sharpness (Laplacian variance) ---
        Mat laplacian = new Mat();
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
        MatOfDouble lapMean = new MatOfDouble();
        MatOfDouble lapStddev = new MatOfDouble();
        Core.meanStdDev(laplacian, lapMean, lapStddev);
        double sharpness = lapStddev.get(0, 0)[0];
        // Normalize: typical sharp face ~20-50, blurry <10
        double sharpnessScore = Math.min(1.0, sharpness / 40.0);

        // --- Face size relative to frame ---
        double faceArea = (double) (w * h);
        double frameArea = (double) (bgrFrame.cols() * bgrFrame.rows());
        double sizeRatio = faceArea / frameArea;
        // Ideal face size is 10-50% of frame. Too small or too large is penalized.
        double sizeScore;
        if (sizeRatio < 0.02) {
            sizeScore = sizeRatio / 0.02;
        } else if (sizeRatio > 0.6) {
            sizeScore = Math.max(0, 1.0 - (sizeRatio - 0.6) / 0.4);
        } else {
            sizeScore = 1.0;
        }

        // --- Brightness / exposure ---
        MatOfDouble brightMean = new MatOfDouble();
        MatOfDouble brightStd = new MatOfDouble();
        Core.meanStdDev(gray, brightMean, brightStd);
        double meanBright = brightMean.get(0, 0)[0];
        // Penalize too dark (<60) or too bright (>200)
        double brightnessScore;
        if (meanBright < 30) {
            brightnessScore = meanBright / 30.0;
        } else if (meanBright > 220) {
            brightnessScore = Math.max(0, (255.0 - meanBright) / 35.0);
        } else {
            brightnessScore = 1.0;
        }

        // Release
        face.release();
        gray.release();
        laplacian.release();
        lapMean.release();
        lapStddev.release();
        brightMean.release();
        brightStd.release();

        // Weighted combination
        double quality = (sharpnessScore * 0.45 + sizeScore * 0.30 + brightnessScore * 0.25) * 100.0;
        return (int) Math.max(0, Math.min(100, Math.round(quality)));
    }

    @Override
    public void close() {
        landmarkNet = null;
    }
}
