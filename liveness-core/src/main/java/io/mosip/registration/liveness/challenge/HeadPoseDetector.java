package io.mosip.registration.liveness.challenge;

import io.mosip.registration.liveness.engine.FaceObservation;

/** Head turn (yaw) or look up/down (pitch) beyond a required angle from the baseline pose. */
final class HeadPoseDetector implements ActionDetector {

    private static final int REQUIRED_FRAMES = 2;

    private final ChallengeType type;
    private final boolean yawAxis;
    private final int direction;
    private final float baseline;
    private final float requiredDegrees;
    private int framesAtTarget;
    /** Set once the head has been seen near the resting pose during this challenge. */
    private boolean seenNeutral;

    HeadPoseDetector(ChallengeType type, FaceBaseline baseline, float yawDegrees, float pitchDegrees) {
        this.type = type;
        switch (type) {
            case TURN_LEFT:
                yawAxis = true;
                direction = 1;
                break;
            case TURN_RIGHT:
                yawAxis = true;
                direction = -1;
                break;
            case LOOK_UP:
                yawAxis = false;
                direction = 1;
                break;
            case LOOK_DOWN:
                yawAxis = false;
                direction = -1;
                break;
            default:
                throw new IllegalArgumentException("Not a head pose challenge: " + type);
        }
        this.baseline = yawAxis ? baseline.getYaw() : baseline.getPitch();
        this.requiredDegrees = yawAxis ? yawDegrees : pitchDegrees;
    }

    @Override
    public ChallengeType type() {
        return type;
    }

    @Override
    public Progress update(FaceObservation face) {
        float moved = direction * (angle(face) - baseline);
        if (moved >= requiredDegrees) {
            return ++framesAtTarget >= REQUIRED_FRAMES ? Progress.COMPLETED : Progress.IN_PROGRESS;
        }
        framesAtTarget = 0;
        return moved >= requiredDegrees * 0.4f ? Progress.IN_PROGRESS : Progress.WAITING;
    }

    /**
     * Only counts after the head has returned near the resting pose: right after a "turn right"
     * challenge the subject is still turned right when "turn left" appears, which is not a wrong action.
     */
    @Override
    public boolean contradicts(FaceObservation face) {
        float moved = angle(face) - baseline;
        if (Math.abs(moved) < requiredDegrees * 0.4f) {
            seenNeutral = true;
        }
        return seenNeutral && -direction * moved >= requiredDegrees;
    }

    private float angle(FaceObservation face) {
        return yawAxis ? face.getYaw() : face.getPitch();
    }
}
