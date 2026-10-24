package io.mosip.registration.liveness.engine;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Per-face measurements reported by a {@link LivenessEngine} for one frame.
 *
 * <p>Geometry is normalised to the frame (0..1). Pose angles are in degrees and subject-centric:
 * positive yaw means the subject turned their head to <em>their</em> left, positive pitch means the
 * subject looks up. Engines must undo any preview mirroring before reporting, so that "turn your
 * head to the left" is unambiguous. Eye openness and smile are 0..1; {@link Float#NaN} means the
 * engine cannot measure the value. Occluded regions are those the engine saw covered; engines that
 * cannot tell report none.
 */
public final class FaceObservation {
    private final float centerX;
    private final float centerY;
    private final float width;
    private final float height;
    private final int quality;
    private final float leftEyeOpenness;
    private final float rightEyeOpenness;
    private final float smile;
    private final float yaw;
    private final float pitch;
    private final float roll;
    private final Set<FaceRegion> occluded;

    private FaceObservation(Builder b) {
        centerX = b.centerX;
        centerY = b.centerY;
        width = b.width;
        height = b.height;
        quality = b.quality;
        leftEyeOpenness = b.leftEyeOpenness;
        rightEyeOpenness = b.rightEyeOpenness;
        smile = b.smile;
        yaw = b.yaw;
        pitch = b.pitch;
        roll = b.roll;
        occluded = b.occluded.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(b.occluded));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mean of the two eyes, ignoring an eye that could not be measured. */
    public float eyeOpenness() {
        if (Float.isNaN(leftEyeOpenness)) {
            return rightEyeOpenness;
        }
        if (Float.isNaN(rightEyeOpenness)) {
            return leftEyeOpenness;
        }
        return (leftEyeOpenness + rightEyeOpenness) / 2f;
    }

    /** Distance between face centres, as a fraction of the frame. */
    public float distanceTo(FaceObservation other) {
        return (float) Math.hypot(centerX - other.centerX, centerY - other.centerY);
    }

    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }
    public float getWidth() { return width; }
    public float getHeight() { return height; }
    /** Face image quality 0..100 (sharpness, exposure, size, occlusion), engine specific. */
    public int getQuality() { return quality; }
    public float getLeftEyeOpenness() { return leftEyeOpenness; }
    public float getRightEyeOpenness() { return rightEyeOpenness; }
    public float getSmile() { return smile; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public float getRoll() { return roll; }
    /** Face regions seen covered in this frame (empty if none, or if the engine cannot tell). */
    public Set<FaceRegion> getOccludedRegions() { return occluded; }
    public boolean isOccluded() { return !occluded.isEmpty(); }

    public static final class Builder {
        private float centerX = 0.5f;
        private float centerY = 0.5f;
        private float width = 0.35f;
        private float height = 0.45f;
        private int quality;
        private float leftEyeOpenness = Float.NaN;
        private float rightEyeOpenness = Float.NaN;
        private float smile = Float.NaN;
        private float yaw;
        private float pitch;
        private float roll;
        private final Set<FaceRegion> occluded = EnumSet.noneOf(FaceRegion.class);

        private Builder() {
        }

        public Builder center(float x, float y) { centerX = x; centerY = y; return this; }
        public Builder size(float w, float h) { width = w; height = h; return this; }
        public Builder quality(int v) { quality = v; return this; }
        public Builder eyes(float left, float right) { leftEyeOpenness = left; rightEyeOpenness = right; return this; }
        public Builder eyes(float both) { return eyes(both, both); }
        public Builder smile(float v) { smile = v; return this; }
        public Builder pose(float yawDeg, float pitchDeg, float rollDeg) { yaw = yawDeg; pitch = pitchDeg; roll = rollDeg; return this; }
        public Builder occluded(Set<FaceRegion> regions) { occluded.clear(); occluded.addAll(regions); return this; }

        public FaceObservation build() {
            return new FaceObservation(this);
        }
    }
}
