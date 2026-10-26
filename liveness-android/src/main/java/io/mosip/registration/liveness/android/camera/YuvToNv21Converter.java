package io.mosip.registration.liveness.android.camera;

import java.nio.ByteBuffer;

/**
 * High-performance converter from Android Camera2 / CameraX YUV_420_888 image planes
 * to standard NV21 format (Y plane followed by interleaved VU bytes).
 *
 * <p>Designed for real-time video processing on Android edge devices: supports writing
 * into pre-allocated reusable byte buffers to eliminate per-frame heap allocations
 * and garbage collection jitter.
 */
public final class YuvToNv21Converter {

    private YuvToNv21Converter() {
    }

    /**
     * Converts Y, U, V byte buffers from CameraX ImageProxy planes to an NV21 byte array.
     *
     * @param yBuffer        direct or array buffer containing the Y plane
     * @param yRowStride     row stride in bytes for the Y plane
     * @param uBuffer        direct or array buffer containing the U plane
     * @param vBuffer        direct or array buffer containing the V plane
     * @param uvRowStride    row stride in bytes for the chroma planes
     * @param uvPixelStride  pixel stride for chroma planes (typically 1 for planar or 2 for semi-planar)
     * @param width          frame width in pixels (must be positive and even)
     * @param height         frame height in pixels (must be positive and even)
     * @param outNv21        output buffer, must have length at least {@code width * height * 3 / 2}
     * @return the {@code outNv21} buffer containing the NV21 frame
     * @throws IllegalArgumentException if dimensions, strides, or buffer sizes are invalid
     */
    public static byte[] convert(ByteBuffer yBuffer, int yRowStride,
                                 ByteBuffer uBuffer, ByteBuffer vBuffer,
                                 int uvRowStride, int uvPixelStride,
                                 int width, int height,
                                 byte[] outNv21) {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("Width and height must be positive even integers: " + width + "x" + height);
        }
        int requiredSize = width * height * 3 / 2;
        if (outNv21 == null || outNv21.length < requiredSize) {
            throw new IllegalArgumentException("Output buffer too small: required " + requiredSize + ", got "
                    + (outNv21 == null ? "null" : outNv21.length));
        }

        int ySize = width * height;

        // 1. Copy Y plane
        yBuffer.rewind();
        if (yRowStride == width) {
            yBuffer.get(outNv21, 0, ySize);
        } else {
            int yPos = 0;
            for (int row = 0; row < height; row++) {
                yBuffer.position(row * yRowStride);
                yBuffer.get(outNv21, yPos, width);
                yPos += width;
            }
        }

        // 2. Interleave V and U chroma bytes (NV21 format: V followed by U)
        uBuffer.rewind();
        vBuffer.rewind();

        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        int uvOffset = ySize;

        // Fast path: Camera2 / CameraX semi-planar interleaved NV21 (V buffer starts 1 byte before U, pixelStride == 2)
        if (uvPixelStride == 2 && uvRowStride == width && vBuffer.remaining() >= chromaHeight * chromaWidth * 2) {
            vBuffer.get(outNv21, uvOffset, chromaHeight * chromaWidth * 2);
            return outNv21;
        }

        // General path: handles arbitrary row and pixel strides
        for (int row = 0; row < chromaHeight; row++) {
            int uRowStart = row * uvRowStride;
            int vRowStart = row * uvRowStride;
            for (int col = 0; col < chromaWidth; col++) {
                int uIdx = uRowStart + col * uvPixelStride;
                int vIdx = vRowStart + col * uvPixelStride;

                byte vVal = vBuffer.get(vIdx);
                byte uVal = uBuffer.get(uIdx);

                outNv21[uvOffset++] = vVal;
                outNv21[uvOffset++] = uVal;
            }
        }

        return outNv21;
    }

    /**
     * Convenience method that converts arrays directly into a freshly allocated NV21 array.
     */
    public static byte[] convert(byte[] yPlane, int yRowStride,
                                 byte[] uPlane, byte[] vPlane,
                                 int uvRowStride, int uvPixelStride,
                                 int width, int height) {
        ByteBuffer yBuf = ByteBuffer.wrap(yPlane);
        ByteBuffer uBuf = ByteBuffer.wrap(uPlane);
        ByteBuffer vBuf = ByteBuffer.wrap(vPlane);
        byte[] out = new byte[width * height * 3 / 2];
        return convert(yBuf, yRowStride, uBuf, vBuf, uvRowStride, uvPixelStride, width, height, out);
    }
}
