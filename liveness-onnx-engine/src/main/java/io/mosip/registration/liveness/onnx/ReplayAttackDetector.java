package io.mosip.registration.liveness.onnx;

import io.mosip.registration.liveness.engine.AttackType;
import io.mosip.registration.liveness.engine.PadIndicator;
import io.mosip.registration.liveness.engine.PadResult;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Screen and photo replay cues that complement the MiniFASNet models:
 *
 * <ul>
 *   <li><b>Moiré:</b> a camera re-capturing a screen aliases the display's pixel grid into periodic
 *       interference. In the whitened spectrum of the face texture this shows up as isolated peaks
 *       that natural skin, with its smoothly falling spectrum, does not have. JPEG block harmonics
 *       and the axes (dominated by edges) are excluded.</li>
 *   <li><b>Lighting uniformity:</b> a backlit display emits light evenly, so the low-frequency
 *       illumination across the face is flatter than the 3D shading of a real face.</li>
 *   <li><b>Bezel/frame edges:</b> long straight edges just outside the face on several sides, as from
 *       a phone, tablet or monitor bezel, or the border of a printed photo.</li>
 * </ul>
 *
 * Each cue yields a suspicion in 0..1. Their weighted sum lowers the liveness score, so mild
 * suspicion escalates to an active challenge, and at {@link #ATTACK_THRESHOLD} the frame is
 * reported as an attack. The weights let a full bezel alone, or strong moiré on flat lighting,
 * reach the threshold, while lighting alone never can.
 *
 * <p>The thresholds are first estimates checked against synthetic images; calibrate them on recorded
 * bona fide and attack sessions (ISO/IEC 30107-3 APCER/BPCER) before relying on them.
 *
 * <p>Instances are stateless and thread-safe.
 */
final class ReplayAttackDetector {

    /** Combined suspicion at or above which the frame is reported as a presentation attack. */
    static final double ATTACK_THRESHOLD = 0.45;
    /** How much full suspicion lowers the bona fide score (0.6 = to 40% of the model score). */
    static final double SCORE_PENALTY = 0.6;
    private static final double W_BEZEL = 0.5;
    private static final double W_MOIRE = 0.3;
    private static final double W_LIGHTING = 0.2;
    /** Per-cue level at which the cue is listed as an indicator. */
    private static final double INDICATOR_LEVEL = 0.5;

    private static final int SPECTRUM_SIZE = 128;
    private static final double BAND_LOW = 0.12;
    private static final double BAND_HIGH = 0.45;
    private static final int AXIS_EXCLUSION = 2;
    private static final int JPEG_BLOCK = 8;
    /** Whitened peak level mapped to suspicion 0 and 1. */
    private static final double MOIRE_LOW = 9.0;
    private static final double MOIRE_HIGH = 16.0;

    private static final int LIGHTING_SIZE = 64;
    /**
     * Coefficient of variation of the blurred face luminance mapped to suspicion 1 and 0. Kept low,
     * because evenly front-lit desks (ring lights) also produce flat faces; this cue only ever adds weight.
     */
    private static final double FLAT_CV = 0.03;
    private static final double SHADED_CV = 0.07;

    private static final double BEZEL_SEARCH_SCALE = 3.0;
    private static final double LINE_MIN_LENGTH = 0.6;
    private static final double LINE_MIN_OVERLAP = 0.6;
    private static final double LINE_STRAIGHTNESS = 0.15;
    private static final double BEZEL_MARGIN = 0.08;

    /** The cues for one face. */
    static final class Assessment {
        final double moire;
        final double lighting;
        final double bezel;
        final double suspicion;
        final Set<PadIndicator> indicators;

        Assessment(double moire, double lighting, double bezel) {
            this.moire = moire;
            this.lighting = lighting;
            this.bezel = bezel;
            this.suspicion = W_BEZEL * bezel + W_MOIRE * moire + W_LIGHTING * lighting;
            this.indicators = EnumSet.noneOf(PadIndicator.class);
            if (moire >= INDICATOR_LEVEL) {
                indicators.add(PadIndicator.MOIRE);
            }
            if (lighting >= INDICATOR_LEVEL) {
                indicators.add(PadIndicator.UNIFORM_LIGHTING);
            }
            if (bezel >= INDICATOR_LEVEL) {
                indicators.add(PadIndicator.BEZEL_EDGES);
            }
        }

        boolean isAttack() {
            return suspicion >= ATTACK_THRESHOLD;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "moire=%.2f lighting=%.2f bezel=%.2f suspicion=%.2f",
                    moire, lighting, bezel, suspicion);
        }
    }

    /** @param face face box inside {@code bgrFrame}, at least 8x8 pixels */
    Assessment analyze(Mat bgrFrame, Rect face) {
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(bgrFrame, gray, Imgproc.COLOR_BGR2GRAY);
            return new Assessment(moire(gray, face), lighting(gray, face), bezel(gray, face));
        } finally {
            gray.release();
        }
    }

    /**
     * Folds the replay cues into the model's PAD result: suspicion lowers the bona fide score, and at
     * the threshold turns a bona fide verdict into a screen attack. A model attack stays an attack.
     */
    static PadResult combine(PadResult model, Assessment replay) {
        if (Float.isNaN(model.getBonaFideScore())) {
            return model;
        }
        float score = (float) (model.getBonaFideScore() * (1 - SCORE_PENALTY * Math.min(1.0, replay.suspicion)));
        Set<PadIndicator> indicators = EnumSet.noneOf(PadIndicator.class);
        indicators.addAll(replay.indicators);
        if (model.isAttackDetected()) {
            indicators.add(PadIndicator.MODEL);
            return PadResult.attack(model.getAttackType(), score, indicators);
        }
        if (replay.isAttack()) {
            AttackType type = replay.bezel >= INDICATOR_LEVEL && replay.moire < INDICATOR_LEVEL
                    && replay.lighting < INDICATOR_LEVEL ? AttackType.UNKNOWN : AttackType.SCREEN_DISPLAY;
            return PadResult.attack(type, score, indicators);
        }
        return PadResult.bonaFide(score, indicators);
    }

    // ---- moiré ----

    static double moire(Mat gray, Rect face) {
        Rect inner = clamp(shrink(face, 0.15), gray.cols(), gray.rows());
        if (inner.width < 16 || inner.height < 16) {
            return 0;
        }
        int n = SPECTRUM_SIZE;
        Mat crop = new Mat(gray, inner);
        Mat small = new Mat();
        Mat f = new Mat();
        Mat window = new Mat();
        Mat complex = new Mat();
        List<Mat> planes = new ArrayList<>();
        Mat magnitude = new Mat();
        try {
            Imgproc.resize(crop, small, new Size(n, n), 0, 0, Imgproc.INTER_AREA);
            small.convertTo(f, CvType.CV_32F);
            Core.subtract(f, Core.mean(f), f);
            Imgproc.createHanningWindow(window, new Size(n, n), CvType.CV_32F);
            Core.multiply(f, window, f);
            Core.dft(f, complex, Core.DFT_COMPLEX_OUTPUT);
            Core.split(complex, planes);
            Core.magnitude(planes.get(0), planes.get(1), magnitude);
            float[] mag = new float[n * n];
            magnitude.get(0, 0, mag);
            // JPEG 8x8 blocks repeat every 8 source pixels, i.e. at multiples of (source size / 8) bins.
            return clamp01((whitenedPeak(mag, n, inner.width / (double) JPEG_BLOCK,
                    inner.height / (double) JPEG_BLOCK) - MOIRE_LOW) / (MOIRE_HIGH - MOIRE_LOW));
        } finally {
            crop.release();
            small.release();
            f.release();
            window.release();
            complex.release();
            for (Mat p : planes) {
                p.release();
            }
            magnitude.release();
        }
    }

    /**
     * Largest spectrum value in the mid/high band divided by the median of its frequency ring, which
     * flattens the natural 1/f fall-off so only isolated periodic peaks stand out.
     */
    static double whitenedPeak(float[] mag, int n, double jpegStepU, double jpegStepV) {
        int rings = n / 2 + 1;
        List<List<Float>> byRing = new ArrayList<>(rings);
        for (int i = 0; i < rings; i++) {
            byRing.add(new ArrayList<>());
        }
        int lo = (int) Math.ceil(BAND_LOW * n);
        int hi = (int) Math.floor(BAND_HIGH * n);
        for (int v = 0; v < n; v++) {
            int fv = v <= n / 2 ? v : v - n;
            for (int u = 0; u < n; u++) {
                int fu = u <= n / 2 ? u : u - n;
                int r = (int) Math.round(Math.hypot(fu, fv));
                if (r >= lo && r <= hi && usable(fu, fv, jpegStepU, jpegStepV)) {
                    byRing.get(r).add(mag[v * n + u]);
                }
            }
        }
        double[] median = new double[rings];
        for (int r = lo; r <= hi; r++) {
            List<Float> ring = byRing.get(r);
            if (ring.size() < 8) {
                continue;
            }
            float[] values = new float[ring.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = ring.get(i);
            }
            Arrays.sort(values);
            median[r] = values[values.length / 2];
        }
        double peak = 0;
        for (int v = 0; v < n; v++) {
            int fv = v <= n / 2 ? v : v - n;
            for (int u = 0; u < n; u++) {
                int fu = u <= n / 2 ? u : u - n;
                int r = (int) Math.round(Math.hypot(fu, fv));
                if (r >= lo && r <= hi && median[r] > 0 && usable(fu, fv, jpegStepU, jpegStepV)) {
                    peak = Math.max(peak, mag[v * n + u] / median[r]);
                }
            }
        }
        return peak;
    }

    private static boolean usable(int fu, int fv, double jpegStepU, double jpegStepV) {
        if (Math.abs(fu) <= AXIS_EXCLUSION || Math.abs(fv) <= AXIS_EXCLUSION) {
            return false;
        }
        return !nearMultiple(Math.abs(fu), jpegStepU) && !nearMultiple(Math.abs(fv), jpegStepV);
    }

    private static boolean nearMultiple(int f, double step) {
        if (step < 2) {
            return false;
        }
        double k = Math.round(f / step);
        return k >= 1 && Math.abs(f - k * step) <= 1.5;
    }

    // ---- lighting ----

    static double lighting(Mat gray, Rect face) {
        Rect inner = clamp(shrink(face, 0.1), gray.cols(), gray.rows());
        if (inner.width < 16 || inner.height < 16) {
            return 0;
        }
        Mat crop = new Mat(gray, inner);
        Mat small = new Mat();
        Mat blurred = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        try {
            Imgproc.resize(crop, small, new Size(LIGHTING_SIZE, LIGHTING_SIZE), 0, 0, Imgproc.INTER_AREA);
            // Blur away texture and small features; what remains is the illumination across the face.
            Imgproc.GaussianBlur(small, blurred, new Size(0, 0), LIGHTING_SIZE / 10.0);
            Core.meanStdDev(blurred, mean, std);
            double m = mean.get(0, 0)[0];
            if (m < 1) {
                return 0;
            }
            double cv = std.get(0, 0)[0] / m;
            return clamp01((SHADED_CV - cv) / (SHADED_CV - FLAT_CV));
        } finally {
            crop.release();
            small.release();
            blurred.release();
            mean.release();
            std.release();
        }
    }

    // ---- bezel / frame ----

    static double bezel(Mat gray, Rect face) {
        Rect search = clamp(MiniFasNetEnsemble.scaledBox(gray.cols(), gray.rows(), face, BEZEL_SEARCH_SCALE),
                gray.cols(), gray.rows());
        Mat roi = new Mat(gray, search);
        Mat edges = new Mat();
        Mat lines = new Mat();
        try {
            Imgproc.Canny(roi, edges, 50, 150);
            double minLength = LINE_MIN_LENGTH * Math.min(face.width, face.height);
            Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 180, 30, minLength, 6);
            double margin = BEZEL_MARGIN * face.width;
            boolean left = false;
            boolean right = false;
            boolean top = false;
            boolean bottom = false;
            int[] l = new int[4];
            for (int i = 0; i < lines.rows(); i++) {
                lines.get(i, 0, l);
                double x1 = l[0] + search.x;
                double y1 = l[1] + search.y;
                double x2 = l[2] + search.x;
                double y2 = l[3] + search.y;
                double dx = Math.abs(x2 - x1);
                double dy = Math.abs(y2 - y1);
                if (dx <= LINE_STRAIGHTNESS * dy) {
                    double x = (x1 + x2) / 2;
                    if (overlap(Math.min(y1, y2), Math.max(y1, y2), face.y, face.y + face.height)
                            >= LINE_MIN_OVERLAP * face.height) {
                        left |= x < face.x - margin;
                        right |= x > face.x + face.width + margin;
                    }
                } else if (dy <= LINE_STRAIGHTNESS * dx) {
                    double y = (y1 + y2) / 2;
                    if (overlap(Math.min(x1, x2), Math.max(x1, x2), face.x, face.x + face.width)
                            >= LINE_MIN_OVERLAP * face.width) {
                        top |= y < face.y - margin;
                        bottom |= y > face.y + face.height + margin;
                    }
                }
            }
            int sides = (left ? 1 : 0) + (right ? 1 : 0) + (top ? 1 : 0) + (bottom ? 1 : 0);
            switch (sides) {
                case 4:
                    return 1.0;
                case 3:
                    return 0.8;
                case 2:
                    // Two parallel edges either side of the face look like a device; a corner (e.g. a door frame) less so.
                    return (left && right) || (top && bottom) ? 0.5 : 0.3;
                default:
                    return 0;
            }
        } finally {
            roi.release();
            edges.release();
            lines.release();
        }
    }

    private static double overlap(double a0, double a1, double b0, double b1) {
        return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
    }

    private static Rect shrink(Rect r, double fraction) {
        int dx = (int) Math.round(r.width * fraction);
        int dy = (int) Math.round(r.height * fraction);
        return new Rect(r.x + dx, r.y + dy, r.width - 2 * dx, r.height - 2 * dy);
    }

    private static Rect clamp(Rect r, int w, int h) {
        int x0 = Math.max(0, r.x);
        int y0 = Math.max(0, r.y);
        int x1 = Math.min(w, r.x + r.width);
        int y1 = Math.min(h, r.y + r.height);
        return new Rect(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
