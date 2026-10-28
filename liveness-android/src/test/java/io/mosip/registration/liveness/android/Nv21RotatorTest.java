package io.mosip.registration.liveness.android;

import io.mosip.registration.liveness.android.camera.Nv21Rotator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Nv21RotatorTest {

    /*
     * A 4x2 frame. Luma, row by row:      Chroma, one V/U pair per 2x2 block:
     *   1 2 3 4                             (51,61) (52,62)
     *   5 6 7 8
     */
    private static final byte[] FRAME = {
            1, 2, 3, 4,
            5, 6, 7, 8,
            51, 61, 52, 62
    };

    @Test
    void ninetyDegreesClockwiseTurnsTheTopRowIntoTheRightColumn() {
        byte[] out = Nv21Rotator.rotate(FRAME, 4, 2, 90, false);
        // 2 wide, 4 tall. Left column is the old bottom row, read left to right going down.
        assertArrayEquals(new byte[]{
                5, 1,
                6, 2,
                7, 3,
                8, 4,
                51, 61,
                52, 62
        }, out);
        assertEquals(2, Nv21Rotator.rotatedWidth(4, 2, 90));
        assertEquals(4, Nv21Rotator.rotatedHeight(4, 2, 90));
    }

    @Test
    void twoSeventyDegreesClockwiseTurnsTheTopRowIntoTheLeftColumn() {
        byte[] out = Nv21Rotator.rotate(FRAME, 4, 2, 270, false);
        assertArrayEquals(new byte[]{
                4, 8,
                3, 7,
                2, 6,
                1, 5,
                52, 62,
                51, 61
        }, out);
    }

    @Test
    void oneEightyDegreesTurnsTheFrameUpsideDown() {
        byte[] out = Nv21Rotator.rotate(FRAME, 4, 2, 180, false);
        assertArrayEquals(new byte[]{
                8, 7, 6, 5,
                4, 3, 2, 1,
                52, 62, 51, 61
        }, out);
        assertEquals(4, Nv21Rotator.rotatedWidth(4, 2, 180));
    }

    @Test
    void mirroringFlipsLeftToRightAfterRotating() {
        assertArrayEquals(new byte[]{
                4, 3, 2, 1,
                8, 7, 6, 5,
                52, 62, 51, 61
        }, Nv21Rotator.rotate(FRAME, 4, 2, 0, true));
        // 90° clockwise then mirrored is a transpose.
        assertArrayEquals(new byte[]{
                1, 5,
                2, 6,
                3, 7,
                4, 8,
                51, 61,
                52, 62
        }, Nv21Rotator.rotate(FRAME, 4, 2, 90, true));
    }

    @Test
    void rotationsComposeOnALargerFrame() {
        int w = 8;
        int h = 6;
        byte[] frame = new byte[w * h * 3 / 2];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (byte) (i * 7 + 3);
        }
        byte[] r90 = Nv21Rotator.rotate(frame, w, h, 90, false);
        byte[] r180 = Nv21Rotator.rotate(r90, h, w, 90, false);
        assertArrayEquals(Nv21Rotator.rotate(frame, w, h, 180, false), r180);
        byte[] r270 = Nv21Rotator.rotate(r180, w, h, 90, false);
        assertArrayEquals(Nv21Rotator.rotate(frame, w, h, 270, false), r270);
        assertArrayEquals(frame, Nv21Rotator.rotate(r270, h, w, 90, false), "four quarter turns change nothing");
        assertArrayEquals(frame, Nv21Rotator.rotate(Nv21Rotator.rotate(frame, w, h, 0, true), w, h, 0, true));
        assertArrayEquals(r270, Nv21Rotator.rotate(frame, w, h, -90, false));
        assertArrayEquals(r90, Nv21Rotator.rotate(frame, w, h, 450, false));
    }

    @Test
    void nothingToDoReturnsTheInputAndABufferCanBeReused() {
        assertSame(FRAME, Nv21Rotator.rotate(FRAME, 4, 2, 0, false));
        assertSame(FRAME, Nv21Rotator.rotate(FRAME, 4, 2, 360, false));
        byte[] reuse = new byte[FRAME.length];
        assertSame(reuse, Nv21Rotator.rotate(FRAME, 4, 2, 180, false, reuse));
    }

    @Test
    void badInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Nv21Rotator.rotate(FRAME, 4, 2, 45, false));
        assertThrows(IllegalArgumentException.class, () -> Nv21Rotator.rotate(FRAME, 3, 2, 90, false));
        assertThrows(IllegalArgumentException.class, () -> Nv21Rotator.rotate(new byte[5], 4, 2, 90, false));
        assertThrows(IllegalArgumentException.class, () -> Nv21Rotator.rotate(FRAME, 4, 2, 90, false, FRAME));
        assertThrows(IllegalArgumentException.class, () -> Nv21Rotator.rotate(FRAME, 4, 2, 90, false, new byte[4]));
    }
}
