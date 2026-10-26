package io.mosip.registration.liveness.gate;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Passes every call through to the device until {@link #hold} is called. After that the liveness session's
 * {@code stopStream}/{@code close} are ignored and frames go to the new listener, so the capture that follows a
 * passed session is watched on the same, uninterrupted stream. {@link #release} really stops the device.
 */
final class HeldStreamDevice implements FaceCaptureDevice {

    private final FaceCaptureDevice device;
    private final FrameListener relay = new Relay();
    private final AtomicBoolean streaming = new AtomicBoolean();
    private volatile FrameListener target;
    private boolean held;

    HeldStreamDevice(FaceCaptureDevice device) {
        this.device = device;
    }

    @Override
    public DeviceInfo info() {
        return device.info();
    }

    @Override
    public DeviceStatus status() {
        return device.status();
    }

    @Override
    public synchronized void open() throws DeviceException {
        if (!streaming.get()) {
            device.open();
        }
    }

    @Override
    public synchronized void startStream(FrameListener listener) throws DeviceException {
        target = listener;
        if (!streaming.get()) {
            device.startStream(relay);
            streaming.set(true);
        }
    }

    @Override
    public synchronized void stopStream() {
        if (held) {
            return;
        }
        target = null;
        if (streaming.getAndSet(false)) {
            device.stopStream();
        }
    }

    @Override
    public synchronized void close() {
        if (held) {
            return;
        }
        stopStream();
        device.close();
    }

    /**
     * Keeps the stream running for {@code next}. Call from the session's onComplete, before the session
     * releases the device.
     *
     * @return false if the stream is no longer running, so the capture cannot be watched
     */
    synchronized boolean hold(FrameListener next) {
        if (!streaming.get()) {
            return false;
        }
        held = true;
        target = next;
        return true;
    }

    synchronized void release() {
        held = false;
        target = null;
        if (streaming.getAndSet(false)) {
            device.stopStream();
        }
        device.close();
    }

    /** Runs on the device thread; takes no lock, so a device that waits for its thread in stopStream cannot deadlock. */
    private final class Relay implements FrameListener {
        @Override
        public void onFrame(FaceFrame frame) {
            FrameListener t = target;
            if (t != null) {
                t.onFrame(frame);
            }
        }

        @Override
        public void onStreamError(DeviceException error) {
            streaming.set(false);
            FrameListener t = target;
            if (t != null) {
                t.onStreamError(error);
            }
        }

        @Override
        public void onStreamEnded() {
            streaming.set(false);
            FrameListener t = target;
            if (t != null) {
                t.onStreamEnded();
            }
        }
    }
}
