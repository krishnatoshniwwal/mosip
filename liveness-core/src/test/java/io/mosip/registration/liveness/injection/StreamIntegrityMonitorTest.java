package io.mosip.registration.liveness.injection;

import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.PixelFormat;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamIntegrityMonitorTest {

    private static final int W = 320;
    private static final int H = 240;

    private long sequence;

    @Test
    void cameraSensorWithReadNoiseIsNeverFlagged() {
        StreamIntegrityMonitor m = new StreamIntegrityMonitor();
        Random noise = new Random(1);
        for (int i = 0; i < 120; i++) {
            assertEquals(Collections.emptySet(), m.update(bgr(scene(i, true), noise, 2.0), true), m.diagnostics());
        }
        assertEquals(0, m.repeats());
        assertTrue(m.noiselessMedian() < 0.5, m.diagnostics());
    }

    /** Webcams with strong temporal noise reduction still change most pixels by a level now and then. */
    @Test
    void heavilyDenoisedCameraStaysBelowTheThreshold() {
        StreamIntegrityMonitor m = new StreamIntegrityMonitor();
        Random noise = new Random(2);
        for (int i = 0; i < 120; i++) {
            assertEquals(Collections.emptySet(), m.update(bgr(scene(0, false), noise, 0.7), true), m.diagnostics());
        }
        assertTrue(m.noiselessMedian() < StreamIntegrityMonitor.NOISELESS_FRACTION, m.diagnostics());
    }

    @Test
    void renderedFramesWithoutSensorNoiseAreFlaggedEvenWhileThePersonMoves() {
        StreamIntegrityMonitor m = new StreamIntegrityMonitor();
        Set<InjectionCue> cues = Collections.emptySet();
        for (int i = 0; i < 40 && cues.isEmpty(); i++) {
            cues = m.update(bgr(scene(i, true), null, 0), true);
        }
        assertEquals(EnumSet.of(InjectionCue.NO_SENSOR_NOISE), cues, m.diagnostics());
    }

    /** A deepfake renderer at half the virtual camera's rate: every frame is sent twice. */
    @Test
    void repeatedFramesAreFlaggedEvenWhenEachCarriesNoise() {
        StreamIntegrityMonitor m = new StreamIntegrityMonitor();
        Random noise = new Random(3);
        Set<InjectionCue> cues = Collections.emptySet();
        int frames = 0;
        for (int i = 0; i < 30 && cues.isEmpty(); i++) {
            FaceFrame rendered = bgr(scene(i, true), noise, 2.0);
            cues = m.update(rendered, true);
            cues = m.update(copy(rendered), true);
            frames += 2;
        }
        assertEquals(EnumSet.of(InjectionCue.REPEATED_FRAMES), cues, m.diagnostics());
        assertEquals(2 * StreamIntegrityMonitor.MIN_REPEATS, frames, "flagged after the third repeat");
    }

    @Test
    void repeatedJpegFramesAreFlaggedWithoutDecodingThem() {
        StreamIntegrityMonitor m = new StreamIntegrityMonitor();
        byte[] still = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, 5, (byte) 0xFF, (byte) 0xD9};
        Set<InjectionCue> cues = Collections.emptySet();
        for (int i = 0; i < StreamIntegrityMonitor.MIN_REPEATS + 1; i++) {
            cues = m.update(new FaceFrame("sbi", sequence++, 0, 640, 480, PixelFormat.JPEG, still.clone(), null), true);
        }
        assertEquals(EnumSet.of(InjectionCue.REPEATED_FRAMES), cues);
    }

    @Test
    void framesWithoutAFaceOrWithoutContrastAreIgnored() {
        StreamIntegrityMonitor m = new StreamIntegrityMonitor();
        FaceFrame still = bgr(scene(0, true), null, 0);
        FaceFrame black = new FaceFrame("cam", 0, 0, W, H, PixelFormat.BGR24, new byte[W * H * 3], null);
        for (int i = 0; i < 40; i++) {
            assertEquals(Collections.emptySet(), m.update(still, false), "no face: not counted");
            assertEquals(Collections.emptySet(), m.update(black, true), "covered lens: not counted");
        }
        assertEquals(0, m.repeats());
    }

    @Test
    void resetStartsAFreshWindow() {
        StreamIntegrityMonitor m = new StreamIntegrityMonitor();
        FaceFrame still = bgr(scene(0, true), null, 0);
        for (int i = 0; i < 5; i++) {
            m.update(still, true);
        }
        assertTrue(m.cues().contains(InjectionCue.REPEATED_FRAMES));
        m.reset();
        assertEquals(Collections.emptySet(), m.cues());
        assertEquals(Collections.emptySet(), m.update(still, true));
    }

    @Test
    void lumaIsReadFromGrayAndNv21FramesToo() {
        byte[] gray = new byte[W * H];
        gray[0] = 100;
        assertEquals(100, StreamIntegrityMonitor.sampleLuma(
                new FaceFrame("cam", 0, 0, W, H, PixelFormat.GRAY8, gray, null))[0]);
        byte[] nv21 = new byte[W * H * 3 / 2];
        nv21[0] = 90;
        assertEquals(90, StreamIntegrityMonitor.sampleLuma(
                new FaceFrame("cam", 0, 0, W, H, PixelFormat.NV21, nv21, null))[0]);
    }

    /** Background gradient and a face-sized ellipse that moves 2 px per frame when {@code moving}. */
    private static double[] scene(int t, boolean moving) {
        double[] v = new double[W * H];
        double cx = W / 2.0 + (moving ? 2 * (t % 40) - 40 : 0);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double dx = (x - cx) / 60;
                double dy = (y - H / 2.0) / 80;
                v[y * W + x] = dx * dx + dy * dy <= 1 ? 170 + 20 * dy : 60 + x * 0.3 + y * 0.2;
            }
        }
        return v;
    }

    private FaceFrame bgr(double[] scene, Random noise, double sigma) {
        byte[] d = new byte[W * H * 3];
        for (int i = 0; i < scene.length; i++) {
            for (int c = 0; c < 3; c++) {
                double v = scene[i] + (noise == null ? 0 : noise.nextGaussian() * sigma);
                d[3 * i + c] = (byte) Math.max(0, Math.min(255, Math.round(v)));
            }
        }
        return new FaceFrame("cam", sequence++, 0, W, H, PixelFormat.BGR24, d, null);
    }

    private FaceFrame copy(FaceFrame f) {
        return new FaceFrame(f.getDeviceId(), sequence++, 0, f.getWidth(), f.getHeight(), f.getFormat(),
                f.getData().clone(), null);
    }
}
