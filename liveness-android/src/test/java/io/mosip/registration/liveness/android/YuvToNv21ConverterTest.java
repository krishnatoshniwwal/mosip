package io.mosip.registration.liveness.android;

import io.mosip.registration.liveness.android.camera.YuvToNv21Converter;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YuvToNv21ConverterTest {

    @Test
    void convertsYuvToNv21WithCorrectInterleaving() {
        int width = 4;
        int height = 4;
        int ySize = width * height; // 16

        // Y plane: 16 bytes (values 1..16)
        byte[] yData = new byte[ySize];
        for (int i = 0; i < ySize; i++) {
            yData[i] = (byte) (i + 1);
        }

        // 4 chroma samples (2x2)
        // U plane: 4 bytes (e.g. 10, 20, 30, 40)
        byte[] uData = new byte[]{10, 20, 30, 40};
        // V plane: 4 bytes (e.g. 50, 60, 70, 80)
        byte[] vData = new byte[]{50, 60, 70, 80};

        byte[] nv21 = YuvToNv21Converter.convert(
                yData, width,
                uData, vData,
                2, 1,
                width, height
        );

        int expectedLength = width * height * 3 / 2; // 24 bytes
        assertEquals(expectedLength, nv21.length);

        // Verify Y plane copied exactly
        for (int i = 0; i < ySize; i++) {
            assertEquals((byte) (i + 1), nv21[i]);
        }

        // Verify UV region is interleaved as V then U:
        // Index 16: V[0] = 50, Index 17: U[0] = 10
        // Index 18: V[1] = 60, Index 19: U[1] = 20
        // Index 20: V[2] = 70, Index 21: U[2] = 30
        // Index 22: V[3] = 80, Index 23: U[3] = 40
        assertEquals(50, nv21[16]);
        assertEquals(10, nv21[17]);
        assertEquals(60, nv21[18]);
        assertEquals(20, nv21[19]);
        assertEquals(70, nv21[20]);
        assertEquals(30, nv21[21]);
        assertEquals(80, nv21[22]);
        assertEquals(40, nv21[23]);
    }

    @Test
    void handlesPaddedRowStrideCorrectly() {
        int width = 4;
        int height = 2;
        int yRowStride = 6; // 2 padding bytes per row

        byte[] yData = new byte[]{
                1, 2, 3, 4, 99, 99,   // row 0 + padding
                5, 6, 7, 8, 99, 99    // row 1 + padding
        };
        byte[] uData = new byte[]{10, 20};
        byte[] vData = new byte[]{30, 40};

        byte[] nv21 = YuvToNv21Converter.convert(
                yData, yRowStride,
                uData, vData,
                2, 1,
                width, height
        );

        assertEquals(12, nv21.length);
        // Padding bytes (99) must be skipped:
        assertEquals(1, nv21[0]);
        assertEquals(2, nv21[1]);
        assertEquals(3, nv21[2]);
        assertEquals(4, nv21[3]);
        assertEquals(5, nv21[4]);
        assertEquals(6, nv21[5]);
        assertEquals(7, nv21[6]);
        assertEquals(8, nv21[7]);

        // Chroma
        assertEquals(30, nv21[8]);
        assertEquals(10, nv21[9]);
        assertEquals(40, nv21[10]);
        assertEquals(20, nv21[11]);
    }

    @Test
    void rejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () ->
                YuvToNv21Converter.convert(new byte[15], 5, new byte[4], new byte[4], 2, 1, 5, 3));
    }
}
