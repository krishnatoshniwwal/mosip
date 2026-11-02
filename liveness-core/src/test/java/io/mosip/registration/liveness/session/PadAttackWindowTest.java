package io.mosip.registration.liveness.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadAttackWindowTest {

    @Test
    void anAttackFlaggedOnEveryFrameIsRejectedAfterMinAttackFrames() {
        PadAttackWindow w = new PadAttackWindow(20, 5);
        for (int i = 1; i < 5; i++) {
            assertFalse(w.add(true), "frame " + i);
        }
        assertTrue(w.add(true));
    }

    @Test
    void strayFramesNeverAddUpOverALongSession() {
        PadAttackWindow w = new PadAttackWindow(20, 5);
        for (int i = 1; i <= 1000; i++) {
            assertFalse(w.add(i % 5 == 0), "frame " + i);  // 4 of any 20
        }
        assertEquals(4, w.flagged());
    }

    @Test
    void framesLeaveTheWindow() {
        PadAttackWindow w = new PadAttackWindow(3, 2);
        w.add(true);
        w.add(false);
        w.add(false);
        assertFalse(w.add(true), "the first flag left the window");
        assertTrue(w.add(true));
        w.reset();
        assertEquals(0, w.flagged());
        assertFalse(w.add(true));
    }

    @Test
    void minAttackFramesMustFitTheWindow() {
        assertThrows(IllegalArgumentException.class, () -> new PadAttackWindow(4, 5));
        assertThrows(IllegalArgumentException.class, () -> new PadAttackWindow(4, 0));
    }
}
