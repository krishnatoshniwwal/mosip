package io.mosip.registration.liveness.camera;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCvCameraDeviceTest {

    @BeforeAll
    static void loadOpenCv() {
        OpenCvLoader.ensureLoaded();
    }

    /** Behaves like a webcam: each read blocks for a while, as a real one does until the next frame is exposed. */
    static final class FakeSource implements OpenCvCameraDevice.FrameSource {
        final long readMs;
        volatile int framesBeforeFailure = Integer.MAX_VALUE;
        volatile boolean released;
        /** Return each frame this many extra times, as Media Foundation does when the camera is slower than the reads. */
        volatile int redeliveries;
        private int frameNumber;
        private int redelivered;

        FakeSource(long readMs) {
            this.readMs = readMs;
        }

        @Override
        public boolean isOpened() {
            return !released;
        }

        @Override
        public boolean read(Mat into) {
            try {
                Thread.sleep(readMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (framesBeforeFailure-- <= 0) {
                return false;
            }
            into.create(4, 4, CvType.CV_8UC3);
            if (redelivered < redeliveries && frameNumber > 0) {
                redelivered++;
            } else {
                redelivered = 0;
                frameNumber++;
            }
            into.setTo(new Scalar(frameNumber % 256, frameNumber / 256 % 256, 0));
            return true;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    static class Recorder implements FrameListener {
        final AtomicInteger frames = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicInteger ended = new AtomicInteger();
        final CountDownLatch twoFrames = new CountDownLatch(2);
        final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public void onFrame(FaceFrame frame) {
            frames.incrementAndGet();
            twoFrames.countDown();
        }

        @Override
        public void onStreamError(DeviceException error) {
            errors.incrementAndGet();
            finished.countDown();
        }

        @Override
        public void onStreamEnded() {
            ended.incrementAndGet();
            finished.countDown();
        }
    }

    private static OpenCvCameraDevice deviceOver(FakeSource source) {
        return new OpenCvCameraDevice(0, 30, index -> source);
    }

    @Test
    void stopReturnsPromptlyAndTheListenerHearsNothingMore() throws Exception {
        FakeSource source = new FakeSource(30);
        OpenCvCameraDevice device = deviceOver(source);
        device.open();
        Recorder listener = new Recorder();
        device.startStream(listener);
        assertTrue(listener.twoFrames.await(2, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        device.stopStream();
        long stopMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertTrue(stopMs < 500, "stopStream took " + stopMs + " ms");
        assertEquals(DeviceStatus.READY, device.status());
        int framesAtStop = listener.frames.get();
        Thread.sleep(150);
        assertEquals(framesAtStop, listener.frames.get(), "no frame after stopStream returns");
        assertEquals(0, listener.ended.get(), "a requested stop is not reported back");
        assertEquals(0, listener.errors.get());

        device.close();
        assertTrue(source.released);
        assertEquals(DeviceStatus.DISCONNECTED, device.status());
    }

    @Test
    void theStreamCanBeRestartedAfterAStop() throws Exception {
        OpenCvCameraDevice device = deviceOver(new FakeSource(20));
        device.open();
        device.startStream(new Recorder());
        device.stopStream();
        Recorder second = new Recorder();
        device.startStream(second);
        assertTrue(second.twoFrames.await(2, TimeUnit.SECONDS));
        device.close();
    }

    @Test
    void aFailedReadIsReportedOnceAsAnErrorOnly() throws Exception {
        FakeSource source = new FakeSource(5);
        source.framesBeforeFailure = 3;
        OpenCvCameraDevice device = deviceOver(source);
        device.open();
        Recorder listener = new Recorder();
        device.startStream(listener);

        assertTrue(listener.finished.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(3, listener.frames.get());
        assertEquals(1, listener.errors.get());
        assertEquals(0, listener.ended.get(), "an error is not followed by a second end-of-stream report");
        assertEquals(DeviceStatus.READY, device.status());
        device.close();
    }

    @Test
    void theListenerCanStopTheStreamFromItsOwnCallback() throws Exception {
        OpenCvCameraDevice device = deviceOver(new FakeSource(5));
        device.open();
        CountDownLatch stopped = new CountDownLatch(1);
        device.startStream(new Recorder() {
            @Override
            public void onFrame(FaceFrame frame) {
                device.stopStream();
                stopped.countDown();
            }
        });
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        assertEquals(DeviceStatus.READY, device.status());
        device.close();
    }

    @Test
    void aFrameTheBackendReturnsAgainIsNotDeliveredTwice() throws Exception {
        FakeSource source = new FakeSource(2);
        source.redeliveries = 2;
        OpenCvCameraDevice device = deviceOver(source);
        device.open();
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch five = new CountDownLatch(5);
        device.startStream(new Recorder() {
            @Override
            public void onFrame(FaceFrame frame) {
                seen.add(Arrays.toString(frame.getData()));
                five.countDown();
            }
        });
        assertTrue(five.await(2, TimeUnit.SECONDS));
        device.close();
        synchronized (seen) {
            for (int i = 1; i < seen.size(); i++) {
                assertNotEquals(seen.get(i - 1), seen.get(i), "frame " + i + " repeats the one before it");
            }
        }
    }

    @Test
    void aCameraThatWillNotOpenIsUnavailable() {
        OpenCvCameraDevice device = new OpenCvCameraDevice(3, 30, index -> null);
        DeviceException e = assertThrows(DeviceException.class, device::open);
        assertEquals(DeviceException.Kind.CAMERA_UNAVAILABLE, e.getKind());
        assertEquals(DeviceStatus.ERROR, device.status());
    }
}
