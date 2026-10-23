package io.mosip.registration.liveness.device;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * One frame of the face stream. The pixel buffer is not copied, for throughput; producers must
 * not modify it after handing the frame over.
 *
 * <p>{@code metadata} carries optional device-supplied values (an L1 device may report a face box
 * or its own quality score). It never leaves the client: audit records do not include frames.
 */
public final class FaceFrame {
    private final String deviceId;
    private final long sequence;
    private final long timestampMillis;
    private final int width;
    private final int height;
    private final PixelFormat format;
    private final byte[] data;
    private final Map<String, Object> metadata;

    public FaceFrame(String deviceId, long sequence, long timestampMillis, int width, int height,
                     PixelFormat format, byte[] data, Map<String, Object> metadata) {
        this.deviceId = deviceId;
        this.sequence = sequence;
        this.timestampMillis = timestampMillis;
        this.width = width;
        this.height = height;
        this.format = format;
        this.data = data;
        this.metadata = metadata == null || metadata.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    /**
     * Structural check before the frame reaches the liveness engine: raw formats must have exactly
     * width*height*bpp bytes, JPEG frames must start with SOI and end with EOI.
     */
    public boolean isWellFormed() {
        if (format == null || data == null || data.length == 0) {
            return false;
        }
        if (format == PixelFormat.JPEG) {
            int n = data.length;
            return n > 4
                    && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
                    && (data[n - 2] & 0xFF) == 0xFF && (data[n - 1] & 0xFF) == 0xD9;
        }
        return width > 0 && height > 0 && data.length == format.expectedLength(width, height);
    }

    public String getDeviceId() { return deviceId; }
    public long getSequence() { return sequence; }
    public long getTimestampMillis() { return timestampMillis; }
    /** Pixel width; 0 if unknown (e.g. a JPEG whose header could not be parsed). */
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public PixelFormat getFormat() { return format; }
    public byte[] getData() { return data; }
    public Map<String, Object> getMetadata() { return metadata; }
}
