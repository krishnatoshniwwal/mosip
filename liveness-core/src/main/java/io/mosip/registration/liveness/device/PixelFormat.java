package io.mosip.registration.liveness.device;

/** Frame encodings a face capture device may deliver. */
public enum PixelFormat {
    GRAY8,
    RGB24,
    BGR24,
    /** Android camera default (YUV 4:2:0, interleaved VU). */
    NV21,
    /** Individual JPEG images, e.g. from an SBI MJPEG {@code /stream}. */
    JPEG;

    /** Exact byte length of a raw frame, or -1 for compressed formats. */
    public long expectedLength(int width, int height) {
        long pixels = (long) width * height;
        switch (this) {
            case GRAY8:
                return pixels;
            case RGB24:
            case BGR24:
                return pixels * 3;
            case NV21:
                return pixels * 3 / 2;
            default:
                return -1;
        }
    }
}
