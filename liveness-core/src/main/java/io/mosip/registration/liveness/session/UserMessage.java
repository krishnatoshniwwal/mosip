package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.challenge.ChallengeType;

/**
 * Text shown to the person in front of the camera. The default text is English; translations are
 * looked up by key through {@link io.mosip.registration.liveness.i18n.LivenessMessages}. Messages
 * never name an attack type or a model score.
 */
public enum UserMessage {
    CHECKING_LIVENESS("liveness.status.checking", "Checking face liveness…"),
    CHALLENGE_BLINK("liveness.challenge.blink", "Please blink"),
    CHALLENGE_SMILE("liveness.challenge.smile", "Please smile"),
    CHALLENGE_TURN_LEFT("liveness.challenge.turnLeft", "Please turn your head to the left"),
    CHALLENGE_TURN_RIGHT("liveness.challenge.turnRight", "Please turn your head to the right"),
    CHALLENGE_LOOK_UP("liveness.challenge.lookUp", "Please look up"),
    CHALLENGE_LOOK_DOWN("liveness.challenge.lookDown", "Please look down"),
    FEEDBACK_CONTINUE("liveness.feedback.continue", "Please continue"),
    FEEDBACK_ACTION_DETECTED("liveness.feedback.actionDetected", "Action detected"),
    FEEDBACK_HOLD_STILL("liveness.feedback.holdStill", "Hold still"),
    SUCCESS("liveness.result.success", "Good, face captured successfully"),
    GUIDE_FACE_IN_AREA("liveness.guide.faceInArea", "Keep your face within the capture area"),
    GUIDE_MOVE_CLOSER("liveness.guide.moveCloser", "Move closer to the camera"),
    GUIDE_MOVE_BACK("liveness.guide.moveBack", "Move back from the camera"),
    GUIDE_REMOVE_FACE_COVERING("liveness.guide.removeFaceCovering", "Please remove any mask or scarf covering your face"),
    GUIDE_REMOVE_SUNGLASSES("liveness.guide.removeSunglasses", "Please remove sunglasses or tinted glasses"),
    GUIDE_ONE_FACE("liveness.guide.oneFace", "Make sure only one face is visible to the camera"),
    GUIDE_LOOK_AT_CAMERA("liveness.guide.lookAtCamera", "Look directly at the camera"),
    GUIDE_FACE_VISIBLE("liveness.guide.faceVisible", "Ensure your face is clearly visible"),
    GUIDE_IMPROVE_LIGHTING("liveness.guide.lighting", "Improve lighting"),
    GUIDE_FOLLOW_ACTION("liveness.guide.followAction", "Follow the prompted action"),
    GUIDE_CHECK_DEVICE("liveness.guide.checkDevice", "Check that the face capture device is connected"),
    FAIL_LIVENESS("liveness.fail.liveness", "We could not verify face liveness. Please try again."),
    FAIL_GENERIC("liveness.fail.generic", "Face verification could not be completed. Please try again."),
    FAIL_DEVICE("liveness.fail.device", "The face capture device is not available. Please try again."),
    FAIL_POSITION("liveness.fail.position", "We could not see your face inside the oval. Please try again."),
    FAIL_CONSENT("liveness.fail.consent", "Face capture cannot continue without permission to use the camera."),
    FAIL_CAMERA_NOT_TRUSTED("liveness.fail.cameraNotTrusted",
            "This camera cannot be used for face verification. Please use the approved face capture device."),
    FAIL_OCCLUDED("liveness.fail.occluded", "Your face appears to be covered. Please uncover your face and try again."),
    FINAL_BLOCKED("liveness.final.blocked",
            "Face verification could not be completed. Please contact your administrator."),
    FINAL_SUPERVISOR_OVERRIDE("liveness.final.supervisorOverride",
            "Face capture could not be completed. Supervisor review is required to continue."),
    FINAL_ALTERNATE_AUTH("liveness.final.alternateAuth",
            "Face verification could not be completed. Please use an alternate authentication method."),
    FINAL_LOCKED_OUT("liveness.final.lockedOut",
            "Too many unsuccessful attempts. Face verification is temporarily locked. Please try again later or contact your supervisor."),
    CANCELLED("liveness.result.cancelled", "Face capture was cancelled.");

    private final String key;
    private final String defaultText;

    UserMessage(String key, String defaultText) {
        this.key = key;
        this.defaultText = defaultText;
    }

    public String key() {
        return key;
    }

    public String defaultText() {
        return defaultText;
    }

    public static UserMessage forChallenge(ChallengeType type) {
        switch (type) {
            case BLINK:
                return CHALLENGE_BLINK;
            case SMILE:
                return CHALLENGE_SMILE;
            case TURN_LEFT:
                return CHALLENGE_TURN_LEFT;
            case TURN_RIGHT:
                return CHALLENGE_TURN_RIGHT;
            case LOOK_UP:
                return CHALLENGE_LOOK_UP;
            default:
                return CHALLENGE_LOOK_DOWN;
        }
    }
}
