package io.mosip.registration.liveness.config;

import io.mosip.registration.liveness.engine.FaceObservation;

/**
 * The oval the subject is asked to place their face in, in normalised frame coordinates (0..1) of the
 * unmirrored device image. A face counts towards liveness and can be captured only when it is
 * {@link Position#INSIDE}: centred in the oval and of a size that fills it. Faces elsewhere in the
 * camera's view are still checked for presentation attacks and extra faces, but are otherwise ignored.
 *
 * <p>The UI draws this same oval, so what the subject sees is exactly what is enforced.
 */
public final class CaptureArea {

    /** Where a face is relative to the capture area. */
    public enum Position {
        INSIDE,
        /** Face centre is too far from the centre of the oval. */
        OFF_CENTER,
        /** Face is too small for the oval: the subject should move closer. */
        TOO_FAR,
        /** Face overflows the oval: the subject should move back. */
        TOO_CLOSE
    }

    private final boolean enabled;
    private final float centerX;
    private final float centerY;
    private final float radiusX;
    private final float radiusY;
    private final float centerTolerance;
    private final float minFill;
    private final float maxFill;

    /**
     * @param centerTolerance largest allowed offset of the face centre, as a fraction of the oval's radii
     * @param minFill         smallest face width as a fraction of the oval width
     * @param maxFill         largest face width as a fraction of the oval width
     */
    public CaptureArea(boolean enabled, float centerX, float centerY, float radiusX, float radiusY,
                       float centerTolerance, float minFill, float maxFill) {
        this.enabled = enabled;
        this.centerX = centerX;
        this.centerY = centerY;
        this.radiusX = radiusX;
        this.radiusY = radiusY;
        this.centerTolerance = centerTolerance;
        this.minFill = minFill;
        this.maxFill = maxFill;
    }

    /** Checks the area against the frame. @throws IllegalArgumentException if it is unusable */
    void validate() {
        if (!(radiusX > 0 && radiusX <= 0.5f && radiusY > 0 && radiusY <= 0.5f)) {
            throw new IllegalArgumentException("capture.area.radiusX and radiusY must be in (0, 0.5]");
        }
        if (centerX - radiusX < 0 || centerX + radiusX > 1 || centerY - radiusY < 0 || centerY + radiusY > 1) {
            throw new IllegalArgumentException("capture.area oval must lie inside the frame");
        }
        if (!(centerTolerance > 0 && centerTolerance <= 1)) {
            throw new IllegalArgumentException("capture.area.centerTolerance must be in (0, 1]");
        }
        if (!(minFill > 0 && maxFill > minFill)) {
            throw new IllegalArgumentException("capture.area.minFill must be positive and below capture.area.maxFill");
        }
    }

    public Position locate(FaceObservation face) {
        if (!enabled) {
            return Position.INSIDE;
        }
        double dx = (face.getCenterX() - centerX) / radiusX;
        double dy = (face.getCenterY() - centerY) / radiusY;
        if (Math.hypot(dx, dy) > centerTolerance) {
            return Position.OFF_CENTER;
        }
        float fill = face.getWidth() / (2 * radiusX);
        if (fill < minFill) {
            return Position.TOO_FAR;
        }
        if (fill > maxFill) {
            return Position.TOO_CLOSE;
        }
        return Position.INSIDE;
    }

    public boolean isEnabled() { return enabled; }
    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }
    public float getRadiusX() { return radiusX; }
    public float getRadiusY() { return radiusY; }
    public float getCenterTolerance() { return centerTolerance; }
    public float getMinFill() { return minFill; }
    public float getMaxFill() { return maxFill; }
}
