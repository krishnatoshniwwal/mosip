package io.mosip.registration.liveness.model;

/**
 * A model package failed verification: bad or missing signature, a file that does not match the
 * manifest, an unlisted file, a downgrade, or an engine too old for the package. The package must
 * not be used.
 */
public class ModelIntegrityException extends Exception {
    private static final long serialVersionUID = 1L;

    public ModelIntegrityException(String message) {
        super(message);
    }

    public ModelIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
