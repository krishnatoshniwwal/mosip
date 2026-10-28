package io.mosip.registration.liveness.injection;

/**
 * Evidence that the frames did not come from a camera sensor looking at the person, but were injected into the
 * capture path (a virtual camera fed by a real-time deepfake, a video file or a still image). Injection attacks
 * bypass the camera, so presentation attack detection (ISO/IEC 30107) does not see them; CEN/TS 18099 covers them.
 * Recorded in the audit trail; never shown to the person in front of the camera.
 */
public enum InjectionCue {
    /** The station has a software camera (OBS Virtual Camera, ManyCam, v4l2loopback, …) the capture can open. */
    VIRTUAL_CAMERA,
    /**
     * Bit-identical frames. A sensor's read noise makes two real frames differ, so repeats mean the frames are
     * produced by software, typically a pipeline whose frame rate does not match the camera's (deepfake → OBS).
     */
    REPEATED_FRAMES,
    /**
     * Mid-tone pixels that stay exactly equal from frame to frame: no temporal sensor noise, as in rendered
     * frames or a decoded video whose static areas are copied unchanged between frames.
     */
    NO_SENSOR_NOISE
}
