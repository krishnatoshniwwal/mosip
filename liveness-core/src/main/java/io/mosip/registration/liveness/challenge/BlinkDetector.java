package io.mosip.registration.liveness.challenge;

import io.mosip.registration.liveness.engine.FaceObservation;

/** Requires an open → closed → open eye sequence, with thresholds relative to the subject's baseline. */
final class BlinkDetector implements ActionDetector {

    private final float openThreshold;
    private final float closedThreshold;
    private boolean seenOpen;
    private boolean seenClosed;

    BlinkDetector(FaceBaseline baseline) {
        float reference = Math.max(baseline.getEyeOpenness(), 0.5f);
        openThreshold = reference * 0.75f;
        closedThreshold = reference * 0.4f;
    }

    @Override
    public ChallengeType type() {
        return ChallengeType.BLINK;
    }

    @Override
    public Progress update(FaceObservation face) {
        float eye = face.eyeOpenness();
        if (Float.isNaN(eye)) {
            return seenClosed ? Progress.IN_PROGRESS : Progress.WAITING;
        }
        if (!seenOpen) {
            seenOpen = eye >= openThreshold;
            return Progress.WAITING;
        }
        if (!seenClosed) {
            seenClosed = eye <= closedThreshold;
            return seenClosed ? Progress.IN_PROGRESS : Progress.WAITING;
        }
        return eye >= openThreshold ? Progress.COMPLETED : Progress.IN_PROGRESS;
    }
}
