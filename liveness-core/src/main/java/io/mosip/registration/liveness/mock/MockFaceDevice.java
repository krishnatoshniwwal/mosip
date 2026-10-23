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

import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Mock L0/L1 face device for development and automated tests. Streams small placeholder frames
 * carrying the {@link SimulatedSubject}'s state, and can simulate being unavailable, disconnecting
 * mid-stream and sending corrupt frames.
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

    private volatile DeviceStatus status = DeviceStatus.DISCONNECTED;
    private volatile boolean available = true;
    private ScheduledExecutorService streamer;
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
            return t;
        });
        streamer.scheduleAtFixedRate(() -> frameListener.onFrame(captureFrame()), 0, 1000L / fps, TimeUnit.MILLISECONDS);
        status = DeviceStatus.STREAMING;
    }

    /** Produces one frame now. Used by the stream and directly by tests that drive time themselves. */
    public FaceFrame captureFrame() {
        byte[] data = new byte[WIDTH * HEIGHT];
        if (corruptFrames.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            data = new byte[WIDTH * HEIGHT / 2];
        }
        return new FaceFrame(info.getDeviceId(), sequence.incrementAndGet(), clock.getAsLong(), WIDTH, HEIGHT,
                PixelFormat.GRAY8, data, Collections.singletonMap(SimulatedFrameTruth.METADATA_KEY, subject.sample()));
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

    private void shutdownStreamer() {
        if (streamer != null) {
            streamer.shutdownNow();
            streamer = null;
        }
    }
}
