package io.mosip.registration.liveness.mock;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceException.Kind;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;
import io.mosip.registration.liveness.engine.FaceObservation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Mock L0/L1 face device for development and automated tests. Streams small frames carrying the
 * {@link SimulatedSubject}'s state, and can simulate being unavailable, disconnecting mid-stream and
 * sending corrupt frames.
 *
 * <p>The pixels are a coarse picture of the scene. Whatever is in front of the camera, the frames carry
 * sensor noise; an {@link SimulatedSubject.Presentation#INJECTED_STREAM injected stream} does not, and repeats
 * every other frame like a deepfake renderer running at half the virtual camera's rate. Injection detection
 * therefore runs on these pixels exactly as it does on a real camera's.
 */
public final class MockFaceDevice implements FaceCaptureDevice {

    private static final int WIDTH = 32;
    private static final int HEIGHT = 24;

    private final SimulatedSubject subject;
    private final int fps;
    private final DeviceInfo info;
    private final LongSupplier clock;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger corruptFrames = new AtomicInteger();
    private final Random sensorNoise = new Random(7);
    private byte[] injectedFrame;

    private volatile DeviceStatus status = DeviceStatus.DISCONNECTED;
    private volatile boolean available = true;
    private ScheduledExecutorService streamer;
    private volatile Thread streamThread;
    private FrameListener listener;

    public MockFaceDevice(SimulatedSubject subject, int fps, DeviceLevel level) {
        this(subject, fps, level, System::currentTimeMillis);
    }

    public MockFaceDevice(SimulatedSubject subject, int fps, DeviceLevel level, LongSupplier clock) {
        this.subject = subject;
        this.fps = fps;
        this.clock = clock;
        this.info = new DeviceInfo("MOCK-FACE-" + level, "MOSIP", "Mock Face Camera", "MOCK-0001", level,
                "1.0.0", EnumSet.of(PixelFormat.GRAY8));
    }

    @Override
    public DeviceInfo info() {
        return info;
    }

    @Override
    public DeviceStatus status() {
        return status;
    }

    public SimulatedSubject subject() {
        return subject;
    }

    /** When false, {@link #open()} fails as if the device were unplugged. */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    /** The next {@code count} frames will be truncated. */
    public void injectCorruptFrames(int count) {
        corruptFrames.set(count);
    }

    @Override
    public synchronized void open() throws DeviceException {
        if (!available) {
            status = DeviceStatus.DISCONNECTED;
            throw new DeviceException(Kind.UNAVAILABLE, "Mock face device not connected", null);
        }
        if (status != DeviceStatus.STREAMING) {
            status = DeviceStatus.READY;
        }
    }

    @Override
    public synchronized void startStream(FrameListener frameListener) throws DeviceException {
        if (status != DeviceStatus.READY) {
            throw new DeviceException(Kind.UNAVAILABLE, "Mock face device is " + status, null);
        }
        listener = frameListener;
        streamer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mock-face-device");
            t.setDaemon(true);
            streamThread = t;
            return t;
        });
        streamer.scheduleAtFixedRate(() -> frameListener.onFrame(captureFrame()), 0, 1000L / fps, TimeUnit.MILLISECONDS);
        status = DeviceStatus.STREAMING;
    }

    /** Produces one frame now. Used by the stream and directly by tests that drive time themselves. */
    public synchronized FaceFrame captureFrame() {
        long seq = sequence.incrementAndGet();
        SimulatedFrameTruth truth = subject.sample();
        byte[] data;
        if (corruptFrames.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            data = new byte[WIDTH * HEIGHT / 2];
        } else if (truth.getPresentation() == SimulatedSubject.Presentation.INJECTED_STREAM) {
            if (injectedFrame == null || seq % 2 == 0) {
                injectedFrame = render(truth, false);
            }
            data = injectedFrame;
        } else {
            injectedFrame = null;
            data = render(truth, true);
        }
        return new FaceFrame(info.getDeviceId(), seq, clock.getAsLong(), WIDTH, HEIGHT,
                PixelFormat.GRAY8, data, Collections.singletonMap(SimulatedFrameTruth.METADATA_KEY, truth));
    }

    /** Background gradient with a brighter ellipse per face; with {@code noise}, ±3 levels of sensor noise. */
    private byte[] render(SimulatedFrameTruth truth, boolean noise) {
        byte[] data = new byte[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int v = 60 + 2 * x + y;
                for (FaceObservation f : truth.getFaces()) {
                    float dx = (x + 0.5f) / WIDTH - f.getCenterX();
                    float dy = (y + 0.5f) / HEIGHT - f.getCenterY();
                    float rx = f.getWidth() / 2;
                    float ry = f.getHeight() / 2;
                    if (rx > 0 && ry > 0 && dx * dx / (rx * rx) + dy * dy / (ry * ry) <= 1) {
                        v = 170;
                    }
                }
                if (noise) {
                    v += sensorNoise.nextInt(7) - 3;
                }
                data[y * WIDTH + x] = (byte) Math.max(0, Math.min(255, v));
            }
        }
        return data;
    }

    /** Drops the stream as if the cable were pulled. */
    public void simulateDisconnect() {
        FrameListener l;
        synchronized (this) {
            if (status != DeviceStatus.STREAMING) {
                return;
            }
            shutdownStreamer();
            status = DeviceStatus.DISCONNECTED;
            l = listener;
            listener = null;
        }
        l.onStreamEnded();
    }

    @Override
    public synchronized void stopStream() {
        shutdownStreamer();
        listener = null;
        if (status == DeviceStatus.STREAMING) {
            status = DeviceStatus.READY;
        }
    }

    @Override
    public synchronized void close() {
        stopStream();
        status = DeviceStatus.DISCONNECTED;
    }

    /**
     * Stops the frame timer and waits for a frame already being delivered, so that, like a real device, nothing
     * reaches the listener after {@link #stopStream()} returns. A listener that stops the stream from the frame
     * thread itself is not waited for.
     */
    private void shutdownStreamer() {
        if (streamer == null) {
            return;
        }
        ScheduledExecutorService stopping = streamer;
        streamer = null;
        stopping.shutdownNow();
        if (Thread.currentThread() != streamThread) {
            try {
                stopping.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
