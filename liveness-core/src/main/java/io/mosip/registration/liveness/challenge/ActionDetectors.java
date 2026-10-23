package io.mosip.registration.liveness.challenge;

/** Creates the detector for a challenge. */
public final class ActionDetectors {

    private ActionDetectors() {
    }

    public static ActionDetector create(ChallengeType type, FaceBaseline baseline, float yawDegrees, float pitchDegrees) {
        switch (type) {
            case BLINK:
                return new BlinkDetector(baseline);
            case SMILE:
                return new SmileDetector(baseline);
            default:
                return new HeadPoseDetector(type, baseline, yawDegrees, pitchDegrees);
        }
    }
}
