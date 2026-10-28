package io.mosip.registration.liveness.android.camera;

/**
 * Rotates, and optionally mirrors, NV21 frames (a full-size Y plane followed by interleaved V/U bytes at half
 * resolution) so they reach face detection upright.
 *
 * <p>The rotation is clockwise, in the sense of CameraX's {@code ImageInfo.getRotationDegrees()}: rotating the
 * image by that amount turns it upright for the current display orientation. A 90° or 270° rotation swaps width
 * and height. Mirroring flips the upright image left to right.
 */
public final class Nv21Rotator {

    private Nv21Rotator() {
    }

    /** Width of the frame after rotating a {@code width x height} frame by {@code rotationDegrees}. */
    public static int rotatedWidth(int width, int height, int rotationDegrees) {
        return swapsSides(normalise(rotationDegrees)) ? height : width;
    }

    /** Height of the frame after rotating a {@code width x height} frame by {@code rotationDegrees}. */
    public static int rotatedHeight(int width, int height, int rotationDegrees) {
        return swapsSides(normalise(rotationDegrees)) ? width : height;
    }

    /** As {@link #rotate(byte[], int, int, int, boolean, byte[])}, into a new array. */
    public static byte[] rotate(byte[] nv21, int width, int height, int rotationDegrees, boolean mirror) {
        return rotate(nv21, width, height, rotationDegrees, mirror, null);
    }

    /**
     * @param nv21            source frame, at least {@code width * height * 3 / 2} bytes
     * @param width           source width, positive and even
     * @param height          source height, positive and even
     * @param rotationDegrees clockwise rotation, a multiple of 90 (negative values and values over 360 are allowed)
     * @param mirror          flip the rotated frame left to right
     * @param out             buffer to reuse, or null to allocate one; must not be {@code nv21}
     * @return the rotated frame; {@code nv21} itself when there is nothing to do
     */
    public static byte[] rotate(byte[] nv21, int width, int height, int rotationDegrees, boolean mirror, byte[] out) {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("Width and height must be positive even integers: " + width + "x" + height);
        }
        if (rotationDegrees % 90 != 0) {
            throw new IllegalArgumentException("Rotation must be a multiple of 90 degrees: " + rotationDegrees);
        }
        int size = width * height * 3 / 2;
        if (nv21 == null || nv21.length < size) {
            throw new IllegalArgumentException("NV21 buffer too small for " + width + "x" + height + ": "
                    + (nv21 == null ? "null" : nv21.length));
        }
        int degrees = normalise(rotationDegrees);
        if (degrees == 0 && !mirror) {
            return nv21;
        }
        if (out == null) {
            out = new byte[size];
        } else if (out == nv21 || out.length < size) {
            throw new IllegalArgumentException("Output buffer must be a separate array of at least " + size + " bytes");
        }

        int outWidth = rotatedWidth(width, height, degrees);
        int outHeight = rotatedHeight(width, height, degrees);

        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int srcIndex = sourceIndex(x, y, outWidth, width, height, degrees, mirror);
                out[y * outWidth + x] = nv21[srcIndex];
            }
        }

        int ySize = width * height;
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        int outChromaWidth = outWidth / 2;
        int outChromaHeight = outHeight / 2;
        for (int y = 0; y < outChromaHeight; y++) {
            for (int x = 0; x < outChromaWidth; x++) {
                int src = ySize + 2 * sourceIndex(x, y, outChromaWidth, chromaWidth, chromaHeight, degrees, mirror);
                int dst = ySize + 2 * (y * outChromaWidth + x);
                out[dst] = nv21[src];
                out[dst + 1] = nv21[src + 1];
            }
        }
        return out;
    }

    /** Index in a {@code width x height} source grid of the sample that lands at ({@code x}, {@code y}). */
    private static int sourceIndex(int x, int y, int outWidth, int width, int height, int degrees, boolean mirror) {
        if (mirror) {
            x = outWidth - 1 - x;
        }
        int sx;
        int sy;
        switch (degrees) {
            case 90:
                sx = y;
                sy = height - 1 - x;
                break;
            case 180:
                sx = width - 1 - x;
                sy = height - 1 - y;
                break;
            case 270:
                sx = width - 1 - y;
                sy = x;
                break;
            default:
                sx = x;
                sy = y;
        }
        return sy * width + sx;
    }

    private static int normalise(int degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    private static boolean swapsSides(int degrees) {
        return degrees == 90 || degrees == 270;
    }
}
