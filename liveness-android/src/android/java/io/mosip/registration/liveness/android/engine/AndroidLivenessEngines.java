package io.mosip.registration.liveness.android.engine;

import android.content.Context;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.LivenessEngineException;
import io.mosip.registration.liveness.model.ModelIntegrityException;
import io.mosip.registration.liveness.onnx.OnnxLivenessEngine;

import java.io.IOException;
import java.security.PublicKey;
import java.util.List;

/**
 * Builds the liveness engine on Android: the same {@link OnnxLivenessEngine} and models as the desktop client
 * (YuNet, MiniFASNet, MediaPipe Face Mesh and Iris), on OpenCV's and ONNX Runtime's Android builds, inside
 * {@link AndroidEdgeLivenessEngine}. Loading takes a few seconds: call it off the main thread, once per app.
 */
public final class AndroidLivenessEngines {

    private AndroidLivenessEngines() {
    }

    /** Production: runs only on a signed model package (see {@link AndroidModelStore#activateSigned}). */
    public static LivenessEngine createVerified(Context context, List<PublicKey> trustedKeys)
            throws LivenessEngineException {
        AndroidOpenCv.ensureLoaded();
        try {
            return new AndroidEdgeLivenessEngine(
                    new OnnxLivenessEngine(AndroidModelStore.activateSigned(context, trustedKeys)));
        } catch (IOException | ModelIntegrityException e) {
            throw new LivenessEngineException("Liveness model package could not be verified: " + e.getMessage(), e);
        }
    }

    /** Development: the models bundled in the APK, without a signature check (the engine logs a warning). */
    public static LivenessEngine createUnverified(Context context) throws LivenessEngineException {
        AndroidOpenCv.ensureLoaded();
        try {
            return new AndroidEdgeLivenessEngine(new OnnxLivenessEngine(AndroidModelStore.extractBundled(context)));
        } catch (IOException e) {
            throw new LivenessEngineException("Liveness models could not be prepared: " + e.getMessage(), e);
        }
    }
}
