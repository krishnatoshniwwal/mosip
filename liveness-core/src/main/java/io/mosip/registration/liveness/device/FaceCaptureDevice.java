package io.mosip.registration.liveness.device;

/**
 * Common face capture device interface (device adapter pattern). One adapter per transport or
 * vendor SDK: SBI MJPEG stream on desktop, Android Camera/vendor SDK on Android, or the mock device
 * for development. Liveness logic only ever sees this interface.
 */
public interface FaceCaptureDevice {

    DeviceInfo info();

    DeviceStatus status();

    /** Checks the device is reachable and ready. */
    void open() throws DeviceException;

    /** Starts delivering frames to {@code listener} until {@link #stopStream()} or a stream failure. */
    void startStream(FrameListener listener) throws DeviceException;

    void stopStream();

    void close();
}
