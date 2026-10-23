package io.mosip.registration.liveness.onnx;

import org.junit.jupiter.api.Test;
import org.opencv.core.Rect;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The enlarged crop must match upstream {@code CropImage._get_new_box} exactly, or the models see
 * different context than they were trained on. Expected corners were produced by the upstream code.
 */
class MiniFasNetEnsembleTest {

    @Test
    void centredFace() {
        assertCrop(640, 480, new Rect(300, 200, 100, 120), 2.7, 215, 98, 485, 422);
        assertCrop(640, 480, new Rect(300, 200, 100, 120), 4.0, 150, 0, 549, 479);
    }

    @Test
    void faceNearTopLeftIsShiftedInsideTheFrame() {
        assertCrop(640, 480, new Rect(10, 5, 120, 150), 2.7, 0, 0, 324, 405);
        assertCrop(640, 480, new Rect(10, 5, 120, 150), 4.0, 0, 0, 383, 479);
    }

    @Test
    void faceNearBottomRightIsShiftedInsideTheFrame() {
        assertCrop(640, 480, new Rect(560, 380, 70, 90), 2.7, 450, 236, 639, 479);
        assertCrop(640, 480, new Rect(560, 380, 70, 90), 4.0, 359, 119, 639, 479);
    }

    @Test
    void scaleIsLimitedWhenTheFaceFillsTheFrame() {
        assertCrop(320, 240, new Rect(100, 40, 150, 190), 2.7, 80, 0, 269, 239);
        assertCrop(320, 240, new Rect(100, 40, 150, 190), 4.0, 80, 0, 269, 239);
    }

    @Test
    void smallFaceFromARealPhoto() {
        assertCrop(548, 342, new Rect(226, 93, 31, 40), 2.7, 199, 59, 283, 167);
        assertCrop(548, 342, new Rect(226, 93, 31, 40), 4.0, 179, 33, 303, 193);
    }

    /** Upstream returns inclusive corners (left, top, right, bottom). */
    private static void assertCrop(int w, int h, Rect face, double scale, int left, int top, int right, int bottom) {
        Rect box = MiniFasNetEnsemble.scaledBox(w, h, face, scale);
        assertEquals(new Rect(left, top, right - left + 1, bottom - top + 1), box, "scale " + scale);
    }
}
