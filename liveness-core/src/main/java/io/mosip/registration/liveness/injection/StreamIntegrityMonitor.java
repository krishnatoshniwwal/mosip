package io.mosip.registration.liveness.injection;

import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.PixelFormat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Looks for frames that were not produced by a camera sensor, whatever the device claims to be. Two cues:
 *
 * <ul>
 *   <li><b>Repeated frames.</b> Read noise makes two real frames differ in some pixel, so a bit-identical repeat
 *       never comes from a sensor. Injection pipelines repeat frames whenever the source (a deepfake renderer, a
 *       video file, a still image) runs slower than the virtual camera's frame rate. Works on JPEG streams too.</li>
 *   <li><b>No sensor noise.</b> On a real sensor most mid-tone pixels change by at least one level between frames
 *       even when nothing moves. Rendered frames and decoded video copy static areas unchanged, so nearly every
 *       pixel stays exactly equal. Motion only lowers that fraction, so a moving person never triggers this cue.</li>
 * </ul>
 *
 * Only frames showing a face and some contrast count, so a covered lens or a black frame is not mistaken for an
 * injected one. Not thread-safe: one monitor per session, fed from the session thread.
 */
public final class StreamIntegrityMonitor {

    /** Face frames over which repeats are counted. */
    static final int WINDOW = 30;
    /** Repeated frames within {@link #WINDOW} that mark the stream as injected. */
    static final int MIN_REPEATS = 3;
    /** Frame pairs kept for the noise cue, and how many are needed before it can be decided. */
    static final int NOISE_WINDOW = 20;
    static final int MIN_NOISE_PAIRS = 12;
    /** Median fraction of unchanged mid-tone pixels above which the frames carry no sensor noise. */
    static final double NOISELESS_FRACTION = 0.92;
    /** Luma samples along the shorter side of the frame. */
    private static final int GRID = 120;
    private static final int MIN_SAMPLES = 200;
    /** Mid-tone range: clipped blacks and whites are equal from frame to frame on any camera. */
    private static final int MID_LOW = 16;
    private static final int MID_HIGH = 239;
    /** Luma standard deviation below which a frame is treated as flat (lens covered, camera starting). */
    private static final double MIN_CONTRAST = 4.0;

    private final ArrayDeque<Long> hashes = new ArrayDeque<>();
    private final ArrayDeque<Boolean> repeatFlags = new ArrayDeque<>();
    private final ArrayDeque<Double> noiseless = new ArrayDeque<>();
    private final CRC32 crc = new CRC32();
    private int[] previousLuma;
    private int faceFrames;

    /** The unchanged-pixel fraction at or above which {@link InjectionCue#NO_SENSOR_NOISE} is reported. */
    public static double noiselessThreshold() {
        return NOISELESS_FRACTION;
    }

    /** Forgets everything seen so far, e.g. at the start of a new attempt. */
    public void reset() {
        hashes.clear();
        repeatFlags.clear();
        noiseless.clear();
        previousLuma = null;
        faceFrames = 0;
    }

    /**
     * @param faceVisible whether the engine found exactly one face in this frame; other frames are ignored
     * @return the cues that are conclusive after this frame; empty if the stream looks like a camera's
     */
    public Set<InjectionCue> update(FaceFrame frame, boolean faceVisible) {
        if (!faceVisible || frame == null || frame.getData() == null) {
            return cues();
        }
        int[] luma = sampleLuma(frame);
        if (luma != null && contrast(luma) < MIN_CONTRAST) {
            return cues();
        }
        faceFrames++;
        crc.reset();
        crc.update(frame.getData(), 0, frame.getData().length);
        long hash = crc.getValue() ^ ((long) frame.getData().length << 32);
        boolean repeat = hashes.contains(hash);
        push(hashes, hash, WINDOW);
        push(repeatFlags, repeat, WINDOW);

        if (luma != null && !repeat) {
            if (previousLuma != null && previousLuma.length == luma.length) {
                double fraction = unchangedFraction(previousLuma, luma);
                if (!Double.isNaN(fraction)) {
                    push(noiseless, fraction, NOISE_WINDOW);
                }
            }
            previousLuma = luma;
        }
        return cues();
    }

    /** The cues that are conclusive now. */
    public Set<InjectionCue> cues() {
        Set<InjectionCue> cues = EnumSet.noneOf(InjectionCue.class);
        if (repeats() >= MIN_REPEATS) {
            cues.add(InjectionCue.REPEATED_FRAMES);
        }
        if (noiseless.size() >= MIN_NOISE_PAIRS && noiselessMedian() >= NOISELESS_FRACTION) {
            cues.add(InjectionCue.NO_SENSOR_NOISE);
        }
        return cues.isEmpty() ? Collections.emptySet() : cues;
    }

    /** Repeated frames among the last {@link #WINDOW} face frames. */
    public int repeats() {
        int n = 0;
        for (Boolean r : repeatFlags) {
            if (r) {
                n++;
            }
        }
        return n;
    }

    /** Median fraction of unchanged mid-tone pixels between consecutive frames; NaN before any pair. */
    public double noiselessMedian() {
        if (noiseless.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(noiseless);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /** Measurements behind the cues, for the audit trail and diagnostic overlays. */
    public String diagnostics() {
        return String.format(Locale.ROOT, "repeats=%d/%d unchangedPixels=%s (%d pairs) faceFrames=%d",
                repeats(), repeatFlags.size(),
                noiseless.isEmpty() ? "n/a" : String.format(Locale.ROOT, "%.3f", noiselessMedian()),
                noiseless.size(), faceFrames);
    }

    /** Luma on a sparse grid, or null for formats whose pixels cannot be read here (JPEG). */
    static int[] sampleLuma(FaceFrame frame) {
        PixelFormat format = frame.getFormat();
        int w = frame.getWidth();
        int h = frame.getHeight();
        byte[] d = frame.getData();
        if (format == null || format == PixelFormat.JPEG || w <= 0 || h <= 0
                || d.length < format.expectedLength(w, h)) {
            return null;
        }
        int step = Math.max(1, Math.min(w, h) / GRID);
        int cols = (w + step - 1) / step;
        int rows = (h + step - 1) / step;
        int[] out = new int[cols * rows];
        int i = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int p = y * w + x;
                switch (format) {
                    case BGR24:
                    case RGB24:
                        // Green: the middle byte in either order, and the channel with the least sensor noise
                        // filtering. Not averaged with R and B, which would smooth away the noise being measured.
                        out[i++] = d[3 * p + 1] & 0xFF;
                        break;
                    default:
                        // GRAY8, and the Y plane that starts an NV21 frame.
                        out[i++] = d[p] & 0xFF;
                        break;
                }
            }
        }
        return out;
    }

    private static double contrast(int[] luma) {
        double sum = 0;
        double sq = 0;
        for (int v : luma) {
            sum += v;
            sq += (double) v * v;
        }
        double mean = sum / luma.length;
        return Math.sqrt(Math.max(0, sq / luma.length - mean * mean));
    }

    /** Fraction of mid-tone samples that did not change at all; NaN with too few mid-tone samples. */
    static double unchangedFraction(int[] a, int[] b) {
        int counted = 0;
        int unchanged = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] >= MID_LOW && a[i] <= MID_HIGH && b[i] >= MID_LOW && b[i] <= MID_HIGH) {
                counted++;
                if (a[i] == b[i]) {
                    unchanged++;
                }
            }
        }
        return counted < MIN_SAMPLES ? Double.NaN : unchanged / (double) counted;
    }

    private static <T> void push(ArrayDeque<T> deque, T value, int max) {
        deque.addLast(value);
        while (deque.size() > max) {
            deque.pollFirst();
        }
    }
}
