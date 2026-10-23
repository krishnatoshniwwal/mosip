package io.mosip.registration.liveness.device;

public class DeviceException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** No device / device service found. */
        UNAVAILABLE,
        /** Device found but the connection or stream request failed. */
        CONNECTION_FAILED,
        /** Device present but its camera cannot be used (busy, permission denied). */
        CAMERA_UNAVAILABLE,
        /** An established stream broke. */
        STREAM_ERROR
    }

    private final Kind kind;

    public DeviceException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
