package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.camera.OpenCvLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real OpenCV round trip: what the recorder writes must be readable the way {@code evaluate} reads it. */
class ClipWriterTest {

    private static final int W = 64;
    private static final int H = 48;

    @TempDir
    Path root;

    @BeforeAll
    static void loadOpenCv() {
        OpenCvLoader.ensureLoaded();
    }

    @Test
    void finishedClipIsReadableFrameByFrame() throws Exception {
        Path target = root.resolve("bona_fide/S01/clip.avi");
        Path scratch = root.resolve(".incomplete");
        try (ClipWriter writer = new ClipWriter(target, scratch, W, H, 15)) {
            for (int i = 0; i < 30; i++) {
                assertTrue(writer.write(frame(i), W, H));
            }
            assertFalse(writer.write(new byte[10], 10, 1), "frames of another size are skipped");
            assertFalse(Files.exists(target), "not visible to the evaluator until finished");
            assertEquals(target, writer.finish());
        }
        assertTrue(Files.isRegularFile(target));
        try (Stream<Path> left = Files.list(scratch)) {
            assertEquals(0, left.count(), "scratch file moved, not copied");
        }

        VideoCapture capture = new VideoCapture(target.toString());
        Mat m = new Mat();
        try {
            assertTrue(capture.isOpened());
            assertEquals(30, (int) capture.get(Videoio.CAP_PROP_FRAME_COUNT));
            int read = 0;
            while (capture.read(m)) {
                assertEquals(W, m.cols());
                assertEquals(H, m.rows());
                read++;
            }
            assertEquals(30, read);
        } finally {
            m.release();
            capture.release();
        }
    }

    @Test
    void abortedClipLeavesNothingInTheDataset() throws Exception {
        Path target = root.resolve("attack/printed_photo/S01/clip.avi");
        Path scratch = root.resolve(".incomplete");
        try (ClipWriter writer = new ClipWriter(target, scratch, W, H, 15)) {
            writer.write(frame(0), W, H);
        }
        assertFalse(Files.exists(target));
        try (Stream<Path> left = Files.list(scratch)) {
            assertEquals(0, left.count());
        }
    }

    private static byte[] frame(int i) {
        byte[] bgr = new byte[W * H * 3];
        for (int p = 0; p < bgr.length; p++) {
            bgr[p] = (byte) ((p + i * 7) & 0xFF);
        }
        return bgr;
    }
}
