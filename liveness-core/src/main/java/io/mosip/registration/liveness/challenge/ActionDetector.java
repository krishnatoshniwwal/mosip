package io.mosip.registration.liveness.challenge;

import io.mosip.registration.liveness.engine.FaceObservation;

/** Recognises one requested facial action from the sequence of face observations. Stateful; one per challenge. */
public interface ActionDetector {

    enum Progress {
        WAITING,
        IN_PROGRESS,
        COMPLETED
    }

    ChallengeType type();

    Progress update(FaceObservation face);

    /**
     * True when the subject clearly performs the opposite action (e.g. turns right when asked to turn
     * left). A pre-recorded video that moves on its own cannot follow a random prompt; this catches it.
     */
    default boolean contradicts(FaceObservation face) {
        return false;
    }
}
