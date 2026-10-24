package io.mosip.registration.liveness.onnx;

import io.mosip.registration.liveness.engine.FaceRegion;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Detects a covered face from the five YuNet landmarks: sunglasses or tinted glasses over the eyes,
 * and a mask or scarf over the nose and mouth.
 *
 * <p>The subject's own skin is the reference: the most skin-like of a forehead patch and two
 * under-eye cheek patches gives the expected chroma and brightness. The region is then judged
 * against that reference rather than a fixed skin model, which keeps it fair across skin tones and
 * lighting.
 *
 * <ul>
 *   <li><b>Eyes:</b> both eye patches much darker than the reference skin and with almost no
 *       skin-coloured pixels (the eyelids and surrounding skin are hidden behind a dark lens). Clear
 *       prescription glasses leave the eyelids visible and are not reported.</li>
 *   <li><b>Lower face:</b> few skin-coloured pixels between the nose and chin, and a texture no busier
 *       than skin. The texture test keeps a full beard, which is not skin-coloured but is heavily
 *       textured, from counting as a mask.</li>
 * </ul>
 *
 * If no reference skin can be found (heavy shadow, extreme lighting), nothing is reported: occlusion
 * handling is a usability aid, and PAD does not depend on it.
 *
 * <p>The thresholds are first estimates checked on synthetic faces; calibrate them on real captures
 * with masks, scarves, sunglasses, beards and a range of skin tones before relying on them.
 *
 * <p>Instances are stateless and thread-safe.
 */
final class OcclusionAnalyzer {

    /** Universal YCrCb skin box, used only to choose the reference patch. */
    private static final int CR_MIN = 133;
    private static final int CR_MAX = 173;
    private static final int CB_MIN = 77;
    private static final int CB_MAX = 127;
    private static final double MIN_REFERENCE_SKIN = 0.35;
    /** Chroma distance and brightness range for a pixel to match the subject's own skin. */
    private static final int CHROMA_TOLERANCE = 12;
    private static final double LUMA_LOW = 0.35;
    private static final double LUMA_HIGH = 1.7;

    private static final double EYE_MAX_LUMA_RATIO = 0.45;
    private static final double EYE_MAX_SKIN = 0.2;
    private static final double LOWER_MAX_SKIN = 0.25;
    private static final double LOWER_MAX_TEXTURE_RATIO = 1.8;

    /** Covered regions plus the measurements behind the decision, for diagnostics (no image data). */
    static final class Result {
        final Set<FaceRegion> regions;
        final String measurements;

        Result(Set<FaceRegion> regions, String measurements) {
            this.regions = regions;
            this.measurements = measurements;
        }
    }

    Set<FaceRegion> analyze(Mat bgrFrame, DetectedFace face) {
        return measure(bgrFrame, face).regions;
    }

    Result measure(Mat bgrFrame, DetectedFace face) {
        Point eyeA = face.getEyeA();
        Point eyeB = face.getEyeB();
        double eyeDist = Math.hypot(eyeA.x - eyeB.x, eyeA.y - eyeB.y);
        if (eyeDist < 12) {
            return new Result(Collections.emptySet(), "face too small to judge occlusion");
        }
        Point eyeMid = new Point((eyeA.x + eyeB.x) / 2, (eyeA.y + eyeB.y) / 2);

        Patch reference = null;
        for (Patch candidate : new Patch[] {
                Patch.of(bgrFrame, eyeMid.x, eyeMid.y - 0.55 * eyeDist, 0.8 * eyeDist, 0.3 * eyeDist),
                Patch.of(bgrFrame, eyeA.x, eyeA.y + 0.35 * eyeDist, 0.35 * eyeDist, 0.25 * eyeDist),
                Patch.of(bgrFrame, eyeB.x, eyeB.y + 0.35 * eyeDist, 0.35 * eyeDist, 0.25 * eyeDist)}) {
            if (candidate != null && (reference == null || candidate.universalSkin > reference.universalSkin)) {
                reference = candidate;
            }
        }
        if (reference == null || reference.universalSkin < MIN_REFERENCE_SKIN || reference.skinY < 1) {
            return new Result(Collections.emptySet(), String.format(Locale.ROOT,
                    "no skin reference (best skin fraction %.2f < %.2f): occlusion not judged",
                    reference == null ? 0 : reference.universalSkin, MIN_REFERENCE_SKIN));
        }
        StringBuilder m = new StringBuilder(String.format(Locale.ROOT, "ref skin=%.2f Y=%.0f Cr=%.0f Cb=%.0f tex=%.1f",
                reference.universalSkin, reference.skinY, reference.skinCr, reference.skinCb, reference.texture));

        Set<FaceRegion> covered = EnumSet.noneOf(FaceRegion.class);
        Patch left = Patch.of(bgrFrame, eyeA.x, eyeA.y, 0.45 * eyeDist, 0.3 * eyeDist);
        Patch right = Patch.of(bgrFrame, eyeB.x, eyeB.y, 0.45 * eyeDist, 0.3 * eyeDist);
        if (left != null && right != null && eyeCovered(left, reference) && eyeCovered(right, reference)) {
            covered.add(FaceRegion.EYES);
        }
        for (Patch eye : new Patch[] {left, right}) {
            if (eye != null) {
                m.append(String.format(Locale.ROOT, " | eye luma=%.2f(<%.2f) skin=%.2f(<%.2f)",
                        eye.meanY / reference.skinY, EYE_MAX_LUMA_RATIO, eye.matchingSkin(reference), EYE_MAX_SKIN));
            }
        }

        Point mouthA = face.getMouthA();
        Point mouthB = face.getMouthB();
        Point nose = face.getNoseTip();
        double mouthY = (mouthA.y + mouthB.y) / 2;
        double x0 = Math.min(mouthA.x, mouthB.x) - 0.25 * eyeDist;
        double x1 = Math.max(mouthA.x, mouthB.x) + 0.25 * eyeDist;
        double y0 = nose.y + 0.1 * eyeDist;
        double y1 = mouthY + 0.35 * eyeDist;
        Patch lower = Patch.of(bgrFrame, (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0);
        if (lower != null && lower.matchingSkin(reference) < LOWER_MAX_SKIN
                && lower.texture < LOWER_MAX_TEXTURE_RATIO * Math.max(reference.texture, 1.0)) {
            covered.add(FaceRegion.LOWER_FACE);
        }
        if (lower != null) {
            m.append(String.format(Locale.ROOT, " | lower skin=%.2f(<%.2f) tex ratio=%.2f(<%.2f)",
                    lower.matchingSkin(reference), LOWER_MAX_SKIN,
                    lower.texture / Math.max(reference.texture, 1.0), LOWER_MAX_TEXTURE_RATIO));
        }
        m.append(" => ").append(covered.isEmpty() ? "uncovered" : covered.toString());
        return new Result(covered, m.toString());
    }

    private static boolean eyeCovered(Patch eye, Patch reference) {
        return eye.meanY / reference.skinY < EYE_MAX_LUMA_RATIO && eye.matchingSkin(reference) < EYE_MAX_SKIN;
    }

    /** Pixel statistics of one rectangular patch, in YCrCb. */
    private static final class Patch {
        final byte[] ycc;
        final int pixels;
        final double meanY;
        final double universalSkin;
        /** Mean Y, Cr, Cb over the patch's universal-skin pixels (0 if none). */
        final double skinY;
        final double skinCr;
        final double skinCb;
        /** Standard deviation of the Laplacian: how busy the texture is. */
        final double texture;

        private Patch(byte[] ycc, int pixels, double texture) {
            this.ycc = ycc;
            this.pixels = pixels;
            this.texture = texture;
            double sumY = 0;
            double sy = 0;
            double scr = 0;
            double scb = 0;
            int skin = 0;
            for (int i = 0; i < pixels; i++) {
                int y = ycc[3 * i] & 0xFF;
                int cr = ycc[3 * i + 1] & 0xFF;
                int cb = ycc[3 * i + 2] & 0xFF;
                sumY += y;
                if (cr >= CR_MIN && cr <= CR_MAX && cb >= CB_MIN && cb <= CB_MAX && y > 40) {
                    skin++;
                    sy += y;
                    scr += cr;
                    scb += cb;
                }
            }
            meanY = sumY / pixels;
            universalSkin = skin / (double) pixels;
            skinY = skin == 0 ? 0 : sy / skin;
            skinCr = skin == 0 ? 0 : scr / skin;
            skinCb = skin == 0 ? 0 : scb / skin;
        }

        /** Fraction of pixels that match the reference patch's skin. */
        double matchingSkin(Patch ref) {
            int match = 0;
            for (int i = 0; i < pixels; i++) {
                int y = ycc[3 * i] & 0xFF;
                int cr = ycc[3 * i + 1] & 0xFF;
                int cb = ycc[3 * i + 2] & 0xFF;
                if (Math.abs(cr - ref.skinCr) <= CHROMA_TOLERANCE && Math.abs(cb - ref.skinCb) <= CHROMA_TOLERANCE
                        && y >= LUMA_LOW * ref.skinY && y <= LUMA_HIGH * ref.skinY) {
                    match++;
                }
            }
            return match / (double) pixels;
        }

        /** @return null if the patch falls (mostly) outside the frame or is too small to judge */
        static Patch of(Mat bgr, double cx, double cy, double w, double h) {
            int x0 = (int) Math.max(0, Math.round(cx - w / 2));
            int y0 = (int) Math.max(0, Math.round(cy - h / 2));
            int x1 = (int) Math.min(bgr.cols(), Math.round(cx + w / 2));
            int y1 = (int) Math.min(bgr.rows(), Math.round(cy + h / 2));
            if (x1 - x0 < 4 || y1 - y0 < 3 || (x1 - x0) * (y1 - y0) < 0.5 * w * h) {
                return null;
            }
            Mat region = new Mat(bgr, new Rect(x0, y0, x1 - x0, y1 - y0));
            Mat ycc = new Mat();
            Mat gray = new Mat();
            Mat lap = new Mat();
            MatOfDouble mean = new MatOfDouble();
            MatOfDouble std = new MatOfDouble();
            try {
                Imgproc.cvtColor(region, ycc, Imgproc.COLOR_BGR2YCrCb);
                byte[] data = new byte[(int) (ycc.total() * ycc.channels())];
                ycc.get(0, 0, data);
                Imgproc.cvtColor(region, gray, Imgproc.COLOR_BGR2GRAY);
                Imgproc.Laplacian(gray, lap, CvType.CV_64F);
                Core.meanStdDev(lap, mean, std);
                return new Patch(data, (int) ycc.total(), std.get(0, 0)[0]);
            } finally {
                region.release();
                ycc.release();
                gray.release();
                lap.release();
                mean.release();
                std.release();
            }
        }
    }
}
