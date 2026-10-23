package io.mosip.registration.liveness.onnx;

import io.mosip.registration.liveness.engine.AttackType;
import io.mosip.registration.liveness.engine.PadResult;

import ai.onnxruntime.OrtException;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Anti-spoofing / Presentation Attack Detection (PAD) analyzer.
 *
 * <p>Uses the MiniFASNet ONNX models ({@link MiniFasNetEnsemble}) when they are in the model
 * directory. Without them it falls back to a <b>heuristic analysis</b> that combines several
 * signals to estimate whether the face is bona fide or a presentation attack:
 *
 * <ul>
 *   <li><b>Frequency analysis</b> — real faces have natural high-frequency texture; printed photos
 *       exhibit halftone dots / moiré patterns visible in the frequency domain.</li>
 *   <li><b>Colour channel analysis</b> — real skin has subsurface scattering characteristics;
 *       photos/screens have different channel distributions.</li>
 *   <li><b>Local Binary Pattern (LBP) texture</b> — real and spoofed faces have measurably
 *       different LBP histogram distributions.</li>
 *   <li><b>Specular reflection detection</b> — unnatural specular highlights may indicate
 *       screen glass or glossy photo paper.</li>
 *   <li><b>Edge sharpness distribution</b> — screen-captured faces tend to have more uniform
 *       edge distributions than real faces.</li>
 * </ul>
 *
 * <p>Instances are <em>not</em> thread-safe.
 */
public final class AntiSpoofAnalyzer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AntiSpoofAnalyzer.class.getName());

    /** Weights for combining heuristic signals. */
    private static final double W_FREQUENCY = 0.25;
    private static final double W_COLOUR = 0.20;
    private static final double W_LBP = 0.20;
    private static final double W_SPECULAR = 0.15;
    private static final double W_EDGE = 0.20;

    private MiniFasNetEnsemble model;

    /**
     * @param modelDir directory that may contain the MiniFASNet ONNX models
     *                 ({@value MiniFasNetEnsemble#V2_FILE}, {@value MiniFasNetEnsemble#V1SE_FILE})
     */
    public AntiSpoofAnalyzer(Path modelDir) {
        MiniFasNetEnsemble loaded = null;
        if (modelDir != null) {
            try {
                loaded = MiniFasNetEnsemble.loadIfPresent(modelDir);
            } catch (OrtException e) {
                LOG.log(Level.WARNING, "Failed to load MiniFASNet models — using heuristic fallback", e);
            }
        }
        this.model = loaded;
        if (model != null) {
            LOG.info("Anti-spoof analyzer: loaded " + model.modelCount() + " MiniFASNet model(s) from " + modelDir);
        } else {
            LOG.warning("Anti-spoof analyzer: MiniFASNet models not available — using heuristic analysis. "
                    + "PAD accuracy will be reduced.");
        }
    }

    /** Whether this analyzer is using the MiniFASNet models. */
    public boolean isUsingModel() {
        return model != null;
    }

    /**
     * Analyze a face region for liveness / presentation attacks.
     *
     * @param bgrFrame  the full BGR frame (the model looks at the area around the face too)
     * @param faceRect  the detected face bounding box
     * @return a {@link PadResult} with the liveness assessment
     * @throws OrtException if model inference fails; this is never reported as bona fide
     */
    public PadResult analyze(Mat bgrFrame, Rect faceRect) throws OrtException {
        int x = Math.max(0, faceRect.x);
        int y = Math.max(0, faceRect.y);
        int w = Math.min(faceRect.width, bgrFrame.cols() - x);
        int h = Math.min(faceRect.height, bgrFrame.rows() - y);

        if (w < 8 || h < 8) {
            return PadResult.notEvaluated();
        }

        Rect safeRect = new Rect(x, y, w, h);
        if (model != null) {
            return model.analyze(bgrFrame, safeRect);
        }
        Mat faceCrop = new Mat(bgrFrame, safeRect);
        try {
            return analyzeHeuristic(faceCrop);
        } finally {
            faceCrop.release();
        }
    }

    /**
     * Return the liveness score (0..100) from the most recent analysis. This is a convenience
     * that derives a score from the PadResult.
     */
    public float livenessScore(PadResult pad) {
        if (pad == null || Float.isNaN(pad.getBonaFideScore())) {
            return 50.0f;
        }
        return pad.getBonaFideScore();
    }

    // ======================================================================
    // Heuristic analysis
    // ======================================================================

    private PadResult analyzeHeuristic(Mat faceCrop) {
        // Resize to a standard analysis size for consistency
        Mat resized = new Mat();
        Imgproc.resize(faceCrop, resized, new Size(128, 128));

        double frequencyScore = analyzeFrequency(resized);
        double colourScore = analyzeColourChannels(resized);
        double lbpScore = analyzeLbpTexture(resized);
        double specularScore = analyzeSpecularReflection(resized);
        double edgeScore = analyzeEdgeDistribution(resized);

        resized.release();

        // Weighted combination → bona fide score 0..100
        double combined = frequencyScore * W_FREQUENCY
                + colourScore * W_COLOUR
                + lbpScore * W_LBP
                + (1.0 - specularScore) * W_SPECULAR   // high specular → lower bona fide
                + edgeScore * W_EDGE;

        float bonaFideScore = (float) Math.max(0.0, Math.min(100.0, combined * 100.0));

        // Determine attack type from the strongest negative signals
        if (bonaFideScore >= 50.0f) {
            return PadResult.bonaFide(bonaFideScore);
        }

        AttackType attackType = determineAttackType(frequencyScore, colourScore, lbpScore,
                specularScore, edgeScore);
        return PadResult.attack(attackType, bonaFideScore);
    }

    /**
     * Frequency analysis using Discrete Cosine Transform (DCT).
     *
     * <p>Real faces have a natural distribution of high-frequency texture. Printed photos exhibit
     * moiré patterns (periodic high-frequency peaks), and screen displays have pixel grid artifacts.
     * We compute the ratio of high-frequency to low-frequency energy in the DCT domain.
     *
     * @return score 0..1 where higher = more likely bona fide
     */
    private double analyzeFrequency(Mat face) {
        Mat gray = new Mat();
        Imgproc.cvtColor(face, gray, Imgproc.COLOR_BGR2GRAY);

        // Convert to float for DCT
        Mat floatGray = new Mat();
        gray.convertTo(floatGray, CvType.CV_32F);
        gray.release();

        // DCT requires even dimensions — pad if necessary
        int rows = floatGray.rows();
        int cols = floatGray.cols();
        if (rows % 2 != 0 || cols % 2 != 0) {
            int newRows = rows + (rows % 2);
            int newCols = cols + (cols % 2);
            Mat padded = new Mat(newRows, newCols, CvType.CV_32F, new Scalar(0));
            floatGray.copyTo(padded.submat(0, rows, 0, cols));
            floatGray.release();
            floatGray = padded;
            rows = newRows;
            cols = newCols;
        }

        // Apply DCT
        Mat dct = new Mat();
        Core.dct(floatGray, dct);
        floatGray.release();

        // Compute absolute values
        Mat absDct = new Mat();
        Core.convertScaleAbs(dct, absDct);
        dct.release();

        // Split into low-frequency (top-left quadrant) and high-frequency (rest)
        int halfRows = rows / 4;
        int halfCols = cols / 4;

        Rect lowFreqRoi = new Rect(0, 0, Math.max(1, halfCols), Math.max(1, halfRows));
        Mat lowFreq = new Mat(absDct, lowFreqRoi);
        double lowEnergy = Core.mean(lowFreq).val[0];
        lowFreq.release();

        // High frequency: bottom-right region
        int hfX = cols / 2;
        int hfY = rows / 2;
        int hfW = Math.max(1, cols - hfX);
        int hfH = Math.max(1, rows - hfY);
        Rect highFreqRoi = new Rect(hfX, hfY, hfW, hfH);
        Mat highFreq = new Mat(absDct, highFreqRoi);
        double highEnergy = Core.mean(highFreq).val[0];
        highFreq.release();

        absDct.release();

        // Real faces have a balanced distribution; printed photos have abnormal high-freq peaks
        // (moiré) or suppressed high-freq (low-res prints)
        if (lowEnergy < 1.0) {
            return 0.5; // Avoid division by zero; inconclusive
        }

        double ratio = highEnergy / lowEnergy;
        // Natural faces typically have ratio in 0.05 - 0.30 range.
        // Moiré patterns push this higher; blurry prints push lower.
        // We score natural range as bona fide.
        if (ratio < 0.01) {
            return 0.2; // Suspiciously low — likely a blurry print
        } else if (ratio > 0.5) {
            return 0.3; // Suspiciously high — possible moiré
        } else {
            // Natural range: interpolate to high score
            return 0.5 + 0.5 * Math.min(1.0, ratio / 0.25);
        }
    }

    /**
     * Colour channel analysis for skin naturalness.
     *
     * <p>Real skin has characteristic colour distributions due to subsurface scattering
     * (haemoglobin, melanin). Screens and prints have different channel relationships.
     * We analyze the ratio and spread of BGR channels in the face region.
     *
     * @return score 0..1 where higher = more likely bona fide
     */
    private double analyzeColourChannels(Mat face) {
        // Convert to YCrCb — skin colour is more separable in this space
        Mat ycrcb = new Mat();
        Imgproc.cvtColor(face, ycrcb, Imgproc.COLOR_BGR2YCrCb);

        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        Core.meanStdDev(ycrcb, mean, stddev);

        double crMean = mean.get(1, 0)[0];
        double cbMean = mean.get(2, 0)[0];
        double crStd = stddev.get(1, 0)[0];
        double cbStd = stddev.get(2, 0)[0];

        ycrcb.release();
        mean.release();
        stddev.release();

        // Natural skin: Cr ∈ [133, 173], Cb ∈ [77, 127] with moderate variance
        // Screens tend to have narrower Cr/Cb variance; prints may shift the means
        double crScore = 1.0;
        if (crMean < 120 || crMean > 185) {
            crScore = 0.3;
        } else if (crMean >= 133 && crMean <= 173) {
            crScore = 1.0;
        } else {
            crScore = 0.6;
        }

        double cbScore = 1.0;
        if (cbMean < 60 || cbMean > 145) {
            cbScore = 0.3;
        } else if (cbMean >= 77 && cbMean <= 127) {
            cbScore = 1.0;
        } else {
            cbScore = 0.6;
        }

        // Variance check: real skin has moderate chrominance variance (5-25).
        // Screens/prints tend to have lower variance.
        double varianceScore = 1.0;
        double avgStd = (crStd + cbStd) / 2.0;
        if (avgStd < 3.0) {
            varianceScore = 0.3; // Too uniform — likely screen
        } else if (avgStd > 35.0) {
            varianceScore = 0.5; // Unusually high — possible processing artifact
        }

        return (crScore * 0.35 + cbScore * 0.35 + varianceScore * 0.30);
    }

    /**
     * Simplified Local Binary Pattern (LBP) texture analysis.
     *
     * <p>Real faces have characteristic micro-texture patterns that differ from printed or
     * screen-displayed faces. We compute a simplified LBP by comparing each pixel with its
     * neighbours, then analyze the histogram distribution.
     *
     * @return score 0..1 where higher = more likely bona fide
     */
    private double analyzeLbpTexture(Mat face) {
        Mat gray = new Mat();
        Imgproc.cvtColor(face, gray, Imgproc.COLOR_BGR2GRAY);

        int rows = gray.rows();
        int cols = gray.cols();

        if (rows < 4 || cols < 4) {
            gray.release();
            return 0.5;
        }

        // Compute simplified LBP: for each pixel, compare with 8 neighbours
        // Count transitions (changes from 0→1 or 1→0 in the circular pattern)
        // "Uniform" LBPs (≤2 transitions) indicate real texture
        byte[] grayData = new byte[rows * cols];
        gray.get(0, 0, grayData);
        gray.release();

        int uniformCount = 0;
        int totalCount = 0;

        // Neighbour offsets for 8-connectivity (clockwise from top-left)
        int[] dx = {-1, 0, 1, 1, 1, 0, -1, -1};
        int[] dy = {-1, -1, -1, 0, 1, 1, 1, 0};

        for (int r = 1; r < rows - 1; r++) {
            for (int c = 1; c < cols - 1; c++) {
                int centerVal = grayData[r * cols + c] & 0xFF;
                int pattern = 0;
                for (int n = 0; n < 8; n++) {
                    int neighbourVal = grayData[(r + dy[n]) * cols + (c + dx[n])] & 0xFF;
                    if (neighbourVal >= centerVal) {
                        pattern |= (1 << n);
                    }
                }

                // Count transitions in circular pattern
                int transitions = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int b1 = (pattern >> bit) & 1;
                    int b2 = (pattern >> ((bit + 1) % 8)) & 1;
                    if (b1 != b2) {
                        transitions++;
                    }
                }

                if (transitions <= 2) {
                    uniformCount++;
                }
                totalCount++;
            }
        }

        if (totalCount == 0) {
            return 0.5;
        }

        // Real faces typically have 55-75% uniform LBPs.
        // Printed photos often have higher uniformity (smoother texture after printing).
        // Screens may have lower uniformity (pixel grid interference).
        double uniformRatio = (double) uniformCount / totalCount;

        if (uniformRatio >= 0.50 && uniformRatio <= 0.80) {
            return 0.7 + 0.3 * (1.0 - Math.abs(uniformRatio - 0.65) / 0.15);
        } else if (uniformRatio > 0.80) {
            return 0.3; // Too uniform — likely printed
        } else {
            return 0.4; // Too non-uniform — possible screen interference
        }
    }

    /**
     * Detect unnatural specular reflections that indicate screen glass or glossy photo paper.
     *
     * <p>We look for small, very bright regions (near-white) that are spatially concentrated,
     * which is characteristic of specular highlights from glass or glossy surfaces.
     *
     * @return score 0..1 where higher = more specular reflection detected (bad for liveness)
     */
    private double analyzeSpecularReflection(Mat face) {
        Mat gray = new Mat();
        Imgproc.cvtColor(face, gray, Imgproc.COLOR_BGR2GRAY);

        // Threshold for near-white pixels (potential specular highlights)
        Mat highlights = new Mat();
        Imgproc.threshold(gray, highlights, 240, 255, Imgproc.THRESH_BINARY);

        // Count highlight pixels
        int totalPixels = gray.rows() * gray.cols();
        int highlightCount = Core.countNonZero(highlights);

        gray.release();
        highlights.release();

        double highlightRatio = (double) highlightCount / totalPixels;

        // Small specular highlights (0.5-5%) suggest glass/screen reflection.
        // Large bright areas might just be overexposure (less suspicious).
        // Very few highlights is normal.
        if (highlightRatio < 0.003) {
            return 0.0; // Normal — no significant highlights
        } else if (highlightRatio < 0.005) {
            return 0.2; // Minor highlights — slightly suspicious
        } else if (highlightRatio < 0.05) {
            return 0.7; // Suspicious specular pattern
        } else {
            return 0.4; // Large bright area — more likely overexposure than specular
        }
    }

    /**
     * Analyze edge sharpness distribution.
     *
     * <p>Real faces have a natural distribution of edge strengths. Screen-captured faces tend to
     * have more uniform edges (due to pixel grid), while high-quality prints may have overly
     * smooth gradients.
     *
     * @return score 0..1 where higher = more likely bona fide
     */
    private double analyzeEdgeDistribution(Mat face) {
        Mat gray = new Mat();
        Imgproc.cvtColor(face, gray, Imgproc.COLOR_BGR2GRAY);

        // Compute Canny edges at two different thresholds to analyze distribution
        Mat edgesLow = new Mat();
        Mat edgesHigh = new Mat();
        Imgproc.Canny(gray, edgesLow, 30, 90);
        Imgproc.Canny(gray, edgesHigh, 80, 200);

        int totalPixels = gray.rows() * gray.cols();
        int lowEdgeCount = Core.countNonZero(edgesLow);
        int highEdgeCount = Core.countNonZero(edgesHigh);

        gray.release();
        edgesLow.release();
        edgesHigh.release();

        double lowEdgeRatio = (double) lowEdgeCount / totalPixels;
        double highEdgeRatio = (double) highEdgeCount / totalPixels;

        // Edge ratio: high/low tells us about edge strength uniformity
        // Real faces: diverse edge strengths → ratio typically 0.2-0.6
        // Screens: more uniform → ratio tends toward 0.5-0.8
        // Blurry prints: fewer edges overall → low ratios
        double edgeRatio = (lowEdgeCount > 0) ? (double) highEdgeCount / lowEdgeCount : 0;

        if (lowEdgeRatio < 0.01) {
            return 0.3; // Too few edges — possibly blurry print
        } else if (edgeRatio > 0.7) {
            return 0.4; // Too uniform — possibly screen
        } else if (edgeRatio >= 0.15 && edgeRatio <= 0.55) {
            return 0.9; // Natural distribution
        } else {
            return 0.6;
        }
    }

    // ======================================================================
    // Attack type classification
    // ======================================================================

    /**
     * Determine the most likely attack type based on which heuristic signals are most negative.
     */
    private AttackType determineAttackType(double frequencyScore, double colourScore,
                                           double lbpScore, double specularScore,
                                           double edgeScore) {
        // High specular reflection → likely screen display
        if (specularScore > 0.5) {
            return AttackType.SCREEN_DISPLAY;
        }

        // Poor frequency score with high LBP uniformity → likely printed photo (moiré or halftone)
        if (frequencyScore < 0.4 && lbpScore < 0.5) {
            return AttackType.PRINTED_PHOTO;
        }

        // Uniform edge distribution → screen display
        if (edgeScore < 0.4) {
            return AttackType.SCREEN_DISPLAY;
        }

        // Abnormal colour channels → could be either, default to unknown
        if (colourScore < 0.4) {
            return AttackType.UNKNOWN;
        }

        return AttackType.UNKNOWN;
    }

    // ======================================================================
    // Utilities
    // ======================================================================

    @Override
    public void close() {
        if (model != null) {
            model.close();
            model = null;
        }
    }
}
