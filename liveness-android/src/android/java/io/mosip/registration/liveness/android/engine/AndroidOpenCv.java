package io.mosip.registration.liveness.android.engine;

import io.mosip.registration.liveness.engine.LivenessEngineException;
import org.opencv.android.OpenCVLoader;

/** Loads OpenCV's native library from the {@code org.opencv:opencv} AAR, once per process. */
public final class AndroidOpenCv {

    private static boolean loaded;

    private AndroidOpenCv() {
    }

    public static synchronized void ensureLoaded() throws LivenessEngineException {
        if (loaded) {
            return;
        }
        if (!OpenCVLoader.initLocal()) {
            throw new LivenessEngineException("OpenCV's native library could not be loaded on this device");
        }
        loaded = true;
    }
}
