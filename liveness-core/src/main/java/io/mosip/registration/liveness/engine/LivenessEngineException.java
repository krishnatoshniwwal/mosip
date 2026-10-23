package io.mosip.registration.liveness.engine;

public class LivenessEngineException extends Exception {
    private static final long serialVersionUID = 1L;

    public LivenessEngineException(String message) {
        super(message);
    }

    public LivenessEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
