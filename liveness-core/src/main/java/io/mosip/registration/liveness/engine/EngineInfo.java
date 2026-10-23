package io.mosip.registration.liveness.engine;

import io.mosip.registration.liveness.challenge.ChallengeType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Identity and capabilities of a liveness engine, recorded with every session for traceability. */
public final class EngineInfo {
    private final String name;
    private final String version;
    private final String modelVersion;
    private final Set<ChallengeType> supportedChallenges;
    private final boolean padSupported;

    public EngineInfo(String name, String version, String modelVersion,
                      Set<ChallengeType> supportedChallenges, boolean padSupported) {
        this.name = name;
        this.version = version;
        this.modelVersion = modelVersion;
        this.supportedChallenges = supportedChallenges.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(supportedChallenges));
        this.padSupported = padSupported;
    }

    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getModelVersion() { return modelVersion; }
    /** Facial actions this engine can measure; challenges outside this set are never issued. */
    public Set<ChallengeType> getSupportedChallenges() { return supportedChallenges; }
    public boolean isPadSupported() { return padSupported; }

    public String describe() {
        return name + " " + version + " (model " + modelVersion + ")";
    }
}
