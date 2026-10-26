package io.mosip.registration.liveness.android.config;

import io.mosip.registration.liveness.config.LivenessConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Android configuration loader for MOSIP face liveness policies.
 *
 * <p>Supports reading {@code liveness.properties} packaged in Android app {@code assets/},
 * stored in the application's internal files directory, or falling back to safe built-in defaults.
 */
public final class AndroidLivenessConfig {

    private static final Logger LOG = Logger.getLogger(AndroidLivenessConfig.class.getName());

    private AndroidLivenessConfig() {
    }

    /**
     * Loads configuration from an input stream (e.g. {@code context.getAssets().open("config/liveness.properties")}).
     */
    public static LivenessConfig loadFromStream(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("InputStream cannot be null");
        }
        Properties props = new Properties();
        props.load(stream);
        return LivenessConfig.fromProperties(props);
    }

    /**
     * Loads configuration from an Android file or falls back to built-in defaults if absent.
     */
    public static LivenessConfig loadOrDefault(File file) {
        if (file != null && file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                return loadFromStream(in);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to load liveness config from " + file.getAbsolutePath() + "; using defaults", e);
            }
        }
        return LivenessConfig.defaults();
    }

    /**
     * Loads configuration from a Path or falls back to built-in defaults.
     */
    public static LivenessConfig loadOrDefault(Path path) {
        if (path != null && path.toFile().isFile()) {
            try {
                return LivenessConfig.load(path);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to load liveness config from " + path + "; using defaults", e);
            }
        }
        return LivenessConfig.defaults();
    }
}
