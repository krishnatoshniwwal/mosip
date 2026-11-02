package io.mosip.registration.liveness.session;

/**
 * PAD verdict over the most recent judged frames: an attack once {@code pad.minAttackFrames} of the last
 * {@code pad.attackWindow} frames were flagged. A sliding share rather than a running count, so a few stray
 * frames on a real face (glare, a turn of the head) never add up over a long session, while a print or screen,
 * flagged on nearly every frame, is still rejected within {@code pad.minAttackFrames} frames.
 */
public final class PadAttackWindow {
    private final boolean[] flags;
    private final int minAttackFrames;
    private int next;
    private int size;
    private int flagged;

    public PadAttackWindow(int window, int minAttackFrames) {
        if (window < 1 || minAttackFrames < 1 || minAttackFrames > window) {
            throw new IllegalArgumentException("need 1 <= minAttackFrames <= window");
        }
        this.flags = new boolean[window];
        this.minAttackFrames = minAttackFrames;
    }

    /** Records one judged frame; returns true once the window holds enough flagged frames. */
    public boolean add(boolean attack) {
        if (size == flags.length) {
            flagged -= flags[next] ? 1 : 0;
        } else {
            size++;
        }
        flags[next] = attack;
        flagged += attack ? 1 : 0;
        next = (next + 1) % flags.length;
        return flagged >= minAttackFrames;
    }

    /** Flagged frames currently in the window. */
    public int flagged() {
        return flagged;
    }

    public void reset() {
        java.util.Arrays.fill(flags, false);
        next = 0;
        size = 0;
        flagged = 0;
    }
}
