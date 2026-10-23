package io.mosip.registration.liveness.config;

/** Invalid or unknown liveness configuration. Raised at load time so misconfiguration fails fast. */
public class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
