package io.mosip.registration.liveness.tools;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes one Motion-JPEG {@code .avi} clip of unmirrored BGR frames. OpenCV reads and writes this format
 * without FFmpeg, so {@code evaluate} can always open it. The clip is written in a scratch folder and moved
 * into the dataset only by {@link #finish()}, so an interrupted recording never leaves a partial clip that
 * would be evaluated.
 */
final class ClipWriter implements AutoCloseable {

    private final Path target;
    private final Path partial;
    private final int width;
    private final int height;
    private final VideoWriter writer;
    private final Mat mat;
    private int frames;
    private boolean done;

    ClipWriter(Path target, Path scratchDir, int width, int height, double fps) throws IOException {
        this.target = target;
        this.width = width;
        this.height = height;
        Files.createDirectories(scratchDir);
        Files.createDirectories(target.getParent());
        this.partial = scratchDir.resolve(target.getFileName());
        // OpenCV's own MJPEG writer: no FFmpeg needed, and the same file format on every machine.
        this.writer = new VideoWriter(partial.toString(), Videoio.CAP_OPENCV_MJPEG,
                VideoWriter.fourcc('M', 'J', 'P', 'G'), fps, new Size(width, height), true);
        if (!writer.isOpened()) {
            writer.release();
            throw new IOException("OpenCV could not create " + partial);
        }
        this.mat = new Mat(height, width, CvType.CV_8UC3);
    }

    /** @param bgr {@code width*height*3} bytes; frames of another size are skipped */
    synchronized boolean write(byte[] bgr, int frameWidth, int frameHeight) {
        if (done || frameWidth != width || frameHeight != height || bgr.length != width * height * 3) {
            return false;
        }
        mat.put(0, 0, bgr);
        writer.write(mat);
        frames++;
        return true;
    }

    synchronized int frames() {
        return frames;
    }

    /** Closes the clip and moves it into the dataset. @return the final path */
    synchronized Path finish() throws IOException {
        if (done) {
            throw new IllegalStateException("Clip already finished or aborted");
        }
        done = true;
        writer.release();
        mat.release();
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    /** Discards the clip. Safe to call after {@link #finish()}. */
    @Override
    public synchronized void close() {
        if (done) {
            return;
        }
        done = true;
        writer.release();
        mat.release();
        try {
            Files.deleteIfExists(partial);
        } catch (IOException ignored) {
            // the scratch folder is outside the evaluated layout, so a leftover file is harmless
        }
    }
}
