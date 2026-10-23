package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.device.FaceFrame;

/**
 * UI callback. Calls arrive on a liveness worker thread (or the device thread for preview frames);
 * marshal to the UI thread ({@code Platform.runLater}, {@code SwingUtilities.invokeLater},
 * Flutter platform channel) before touching widgets.
 */
public interface LivenessListener {

    void onUpdate(LivenessUpdate update);

    /** Called exactly once, when the session reaches PASSED, FAILED or CANCELLED. */
    void onComplete(LivenessResult result);

    /** Every frame from the device, for the live preview. */
    default void onPreviewFrame(FaceFrame frame) {
    }
}
