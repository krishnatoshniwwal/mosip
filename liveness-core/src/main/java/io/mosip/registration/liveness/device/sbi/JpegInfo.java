package io.mosip.registration.liveness.device.sbi;

/** Reads image dimensions from a JPEG header without decoding it. */
public final class JpegInfo {

    private JpegInfo() {
    }

    /** @return {width, height}, or null if no SOF marker is found */
    public static int[] dimensions(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return null;
        }
        int i = 2;
        while (i + 3 < jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++; // fill byte
                continue;
            }
            if (marker == 0x01 || marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2; // markers without a length field
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return null; // end of image / start of scan before any SOF
            }
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                if (i + 8 >= jpeg.length) {
                    return null;
                }
                int height = ((jpeg[i + 5] & 0xFF) << 8) | (jpeg[i + 6] & 0xFF);
                int width = ((jpeg[i + 7] & 0xFF) << 8) | (jpeg[i + 8] & 0xFF);
                return new int[] {width, height};
            }
            int segmentLength = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (segmentLength < 2) {
                return null;
            }
            i += 2 + segmentLength;
        }
        return null;
    }
}
