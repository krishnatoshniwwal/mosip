package io.mosip.registration.liveness.challenge;

import io.mosip.registration.liveness.engine.FaceObservation;

/** Requires the smile score to rise clearly above the baseline and hold for consecutive frames. */
final class SmileDetector implements ActionDetector {

    private static final int REQUIRED_FRAMES = 2;

    private final float startThreshold;
    private final float targetThreshold;
    private int framesAtTarget;

    SmileDetector(FaceBaseline baseline) {
        float base = baseline.getSmile();
        startThreshold = base + 0.15f;
        targetThreshold = Math.min(0.9f, Math.max(0.6f, base + 0.35f));
    }

    @Override
    public ChallengeType type() {
        return ChallengeType.SMILE;
    }

    @Override
    public Progress update(FaceObservation face) {
        float smile = face.getSmile();
        if (Float.isNaN(smile)) {
            return Progress.WAITING;
        }
        if (smile >= targetThreshold) {
            return ++framesAtTarget >= REQUIRED_FRAMES ? Progress.COMPLETED : Progress.IN_PROGRESS;
        }
        framesAtTarget = 0;
        return smile >= startThreshold ? Progress.IN_PROGRESS : Progress.WAITING;
    }
}
