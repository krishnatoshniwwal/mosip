package io.mosip.registration.liveness.session;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.mosip.registration.liveness.session.UserMessage.*;

/**
 * Why an attempt failed. Device, frame and engine faults are not the subject's doing, so they do
 * not use up the retry budget. A configuration error cannot be fixed by retrying and ends the session.
 */
public enum FailureReason {
    DEVICE_UNAVAILABLE(false, false, FAIL_DEVICE, GUIDE_CHECK_DEVICE),
    DEVICE_CONNECTION_FAILED(false, false, FAIL_DEVICE, GUIDE_CHECK_DEVICE),
    DEVICE_DISCONNECTED(false, false, FAIL_DEVICE, GUIDE_CHECK_DEVICE),
    CAMERA_UNAVAILABLE(false, false, FAIL_DEVICE, GUIDE_CHECK_DEVICE),
    INVALID_FRAME_DATA(false, false, FAIL_DEVICE, GUIDE_CHECK_DEVICE),
    ENGINE_ERROR(false, false, FAIL_GENERIC),
    CONFIGURATION_ERROR(false, true, FAIL_GENERIC),
    FACE_NOT_DETECTED(true, false, FAIL_LIVENESS, GUIDE_FACE_IN_AREA, GUIDE_LOOK_AT_CAMERA, GUIDE_IMPROVE_LIGHTING),
    MULTIPLE_FACES(true, false, FAIL_LIVENESS, GUIDE_ONE_FACE),
    POOR_FACE_QUALITY(true, false, FAIL_LIVENESS, GUIDE_IMPROVE_LIGHTING, GUIDE_FACE_VISIBLE, GUIDE_LOOK_AT_CAMERA),
    LIVENESS_BELOW_THRESHOLD(true, false, FAIL_LIVENESS, GUIDE_LOOK_AT_CAMERA, GUIDE_FACE_VISIBLE, GUIDE_IMPROVE_LIGHTING),
    PAD_ATTACK_DETECTED(true, false, FAIL_GENERIC, GUIDE_LOOK_AT_CAMERA),
    CHALLENGE_FAILED(true, false, FAIL_LIVENESS, GUIDE_FOLLOW_ACTION, GUIDE_FACE_IN_AREA),
    CHALLENGE_TIMEOUT(true, false, FAIL_LIVENESS, GUIDE_FOLLOW_ACTION, GUIDE_FACE_IN_AREA),
    /** Final result only: the configured retry limit was reached. */
    MAX_RETRIES_EXCEEDED(true, true, FAIL_LIVENESS);

    private final boolean consumesAttempt;
    private final boolean terminal;
    private final UserMessage message;
    private final List<UserMessage> tips;

    FailureReason(boolean consumesAttempt, boolean terminal, UserMessage message, UserMessage... tips) {
        this.consumesAttempt = consumesAttempt;
        this.terminal = terminal;
        this.message = message;
        this.tips = Collections.unmodifiableList(Arrays.asList(tips));
    }

    public boolean consumesAttempt() {
        return consumesAttempt;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public UserMessage message() {
        return message;
    }

    public List<UserMessage> tips() {
        return tips;
    }
}
