package io.mosip.registration.liveness.camera;

/**
 * Loads the OpenCV native library. Call {@link #ensureLoaded()} once before
 * using any OpenCV class. The org.openpnp:opencv dependency handles extraction
 * from the JAR automatically via {@code nu.pattern.OpenCV.loadLocally()}.
 */
public final class OpenCvLoader {
    private static volatile boolean loaded;

    public static synchronized void ensureLoaded() {
        if (!loaded) {
            nu.pattern.OpenCV.loadLocally();
            loaded = true;
        }
    }

    private OpenCvLoader() {}
}
