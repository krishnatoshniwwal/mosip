package io.mosip.registration.liveness.device;

/** Receives the face stream. Called on the device's own thread; implementations must return quickly. */
public interface FrameListener {

    void onFrame(FaceFrame frame);

    /** The stream failed; no more frames will arrive. */
    void onStreamError(DeviceException error);

    /** The device closed the stream or was disconnected; no more frames will arrive. */
    void onStreamEnded();
}
