package io.mosip.registration.liveness.evidence;

import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.device.sbi.MiniJson;
import io.mosip.registration.liveness.gate.GateResult;
import io.mosip.registration.liveness.gate.GateStatus;
import io.mosip.registration.liveness.session.FailureReason;
import io.mosip.registration.liveness.session.LivenessResult;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What the station verified about one face capture, in a form the server can check: the liveness decision, the
 * challenges asked, the scores, and a SHA-256 of the exact biometric bytes the client stores (the MDS-signed face
 * from rCapture). Signed as a JWS by {@link LivenessEvidenceJws} and carried in the registration packet, it lets
 * the server tell a liveness-verified face from one that was never checked, and the hash stops the evidence of one
 * session from being attached to a different face.
 *
 * <p>No biometric data: only decisions, scores, names and hashes. The subject key (registration ID or user ID) is
 * stored as a SHA-256 so the evidence does not add another copy of it.
 */
public final class LivenessEvidence {

    public static final String FORMAT = "mosip.liveness.evidence/1";

    private final String sessionId;
    private final String workflow;
    private final String outcome;
    private final String gateStatus;
    private final float passiveScore;
    private final boolean activeVerificationPerformed;
    private final List<String> challenges;
    private final int attempts;
    private final List<String> attemptFailures;
    private final String engine;
    private final String modelPackage;
    private final String captureSha256;
    private final String subjectSha256;
    private final String stationId;
    private final long issuedAt;
    private final long durationMs;

    private LivenessEvidence(Builder b) {
        this.sessionId = require(b.sessionId, "sessionId");
        this.workflow = require(b.workflow, "workflow");
        this.outcome = require(b.outcome, "outcome");
        this.gateStatus = require(b.gateStatus, "gateStatus");
        this.passiveScore = b.passiveScore;
        this.activeVerificationPerformed = b.activeVerificationPerformed;
        this.challenges = Collections.unmodifiableList(new ArrayList<>(b.challenges));
        this.attempts = b.attempts;
        this.attemptFailures = Collections.unmodifiableList(new ArrayList<>(b.attemptFailures));
        this.engine = require(b.engine, "engine");
        this.modelPackage = b.modelPackage;
        this.captureSha256 = b.captureSha256;
        this.subjectSha256 = b.subjectSha256;
        this.stationId = require(b.stationId, "stationId");
        this.issuedAt = b.issuedAt;
        this.durationMs = b.durationMs;
    }

    /**
     * Evidence for a finished gate run, accepted or not. A failed run is evidence too: a resident sent to
     * supervisor review after the last attempt carries a signed record that liveness did not pass.
     *
     * @param capturedBiometric the bytes the client stores or matches (e.g. the decoded rCapture {@code bioValue});
     *                          null if the capture did not run
     * @param subjectKey        the registration ID for a resident, the user ID for an operator or supervisor
     * @param stationId         the client's machine ID, as registered on the server
     * @param modelPackage      the active signed model package ({@code name version}), or null
     */
    public static LivenessEvidence of(GateResult<?> result, byte[] capturedBiometric, String subjectKey,
                                      String stationId, String modelPackage, Instant issuedAt) {
        Objects.requireNonNull(result, "result");
        LivenessResult l = result.getLiveness();
        Builder b = builder()
                .gateStatus(result.getStatus().name())
                .stationId(stationId)
                .modelPackage(modelPackage)
                .issuedAt(issuedAt.getEpochSecond())
                .captureSha256(capturedBiometric == null ? null : sha256Hex(capturedBiometric))
                .subjectSha256(subjectKey == null ? null : sha256Hex(subjectKey.getBytes(StandardCharsets.UTF_8)));
        if (l == null) {
            // Cancelled before the session produced a result.
            return b.sessionId("none").workflow("UNKNOWN").outcome("CANCELLED").engine("none").build();
        }
        List<String> challenges = new ArrayList<>();
        for (ChallengeType c : l.getChallengesCompleted()) {
            challenges.add(c.name());
        }
        List<String> failures = new ArrayList<>();
        for (FailureReason f : l.getAttemptFailures()) {
            failures.add(f.name());
        }
        return b.sessionId(l.getSessionId())
                .workflow(l.getWorkflow().name())
                .outcome(l.getOutcome().name())
                .passiveScore(l.getPassiveScore())
                .activeVerificationPerformed(l.isActiveVerificationPerformed())
                .challenges(challenges)
                .attempts(l.getAttempts())
                .attemptFailures(failures)
                .engine(l.getEngine())
                .durationMs(l.getDurationMs())
                .build();
    }

    /** True when liveness passed and the capture completed with the verified person still in view. */
    public boolean isLivenessPassed() {
        return "PASSED".equals(outcome) && GateStatus.ACCEPTED.name().equals(gateStatus);
    }

    public String getSessionId() { return sessionId; }
    public String getWorkflow() { return workflow; }
    /** {@code LivenessOutcome} name. */
    public String getOutcome() { return outcome; }
    /** {@code GateStatus} name. */
    public String getGateStatus() { return gateStatus; }
    /** NaN if the passive check was not reached. */
    public float getPassiveScore() { return passiveScore; }
    public boolean isActiveVerificationPerformed() { return activeVerificationPerformed; }
    public List<String> getChallenges() { return challenges; }
    public int getAttempts() { return attempts; }
    public List<String> getAttemptFailures() { return attemptFailures; }
    public String getEngine() { return engine; }
    /** Null if the station did not report one. */
    public String getModelPackage() { return modelPackage; }
    /** Lower-case hex SHA-256 of the stored biometric; null if no capture ran. */
    public String getCaptureSha256() { return captureSha256; }
    /** Lower-case hex SHA-256 of the subject key; null if none was given. */
    public String getSubjectSha256() { return subjectSha256; }
    public String getStationId() { return stationId; }
    /** Epoch seconds. */
    public long getIssuedAt() { return issuedAt; }
    public long getDurationMs() { return durationMs; }

    /** Compact JSON with a fixed key order, the JWS payload. */
    public String toJson() {
        StringBuilder j = new StringBuilder(512).append('{');
        field(j, "format", FORMAT);
        field(j, "sid", sessionId);
        field(j, "workflow", workflow);
        field(j, "outcome", outcome);
        field(j, "gate", gateStatus);
        j.append(",\"passiveScore\":").append(Float.isNaN(passiveScore)
                ? "null" : String.format(Locale.ROOT, "%.2f", passiveScore));
        j.append(",\"active\":").append(activeVerificationPerformed);
        array(j, "challenges", challenges);
        j.append(",\"attempts\":").append(attempts);
        array(j, "attemptFailures", attemptFailures);
        field(j, "engine", engine);
        field(j, "modelPackage", modelPackage);
        field(j, "captureSha256", captureSha256);
        field(j, "subjectSha256", subjectSha256);
        field(j, "stationId", stationId);
        j.append(",\"iat\":").append(issuedAt);
        j.append(",\"durationMs\":").append(durationMs);
        return j.append('}').toString();
    }

    /** @throws IllegalArgumentException if the JSON is malformed, of another format, or misses a required field */
    public static LivenessEvidence fromJson(String json) {
        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("evidence is not a JSON object");
        }
        Map<?, ?> m = (Map<?, ?>) parsed;
        if (!FORMAT.equals(m.get("format"))) {
            throw new IllegalArgumentException("not " + FORMAT + ": " + m.get("format"));
        }
        Object score = m.get("passiveScore");
        return builder()
                .sessionId(string(m, "sid"))
                .workflow(string(m, "workflow"))
                .outcome(string(m, "outcome"))
                .gateStatus(string(m, "gate"))
                .passiveScore(score instanceof Double ? ((Double) score).floatValue() : Float.NaN)
                .activeVerificationPerformed(Boolean.TRUE.equals(m.get("active")))
                .challenges(strings(m, "challenges"))
                .attempts((int) number(m, "attempts"))
                .attemptFailures(strings(m, "attemptFailures"))
                .engine(string(m, "engine"))
                .modelPackage(string(m, "modelPackage"))
                .captureSha256(string(m, "captureSha256"))
                .subjectSha256(string(m, "subjectSha256"))
                .stationId(string(m, "stationId"))
                .issuedAt(number(m, "iat"))
                .durationMs(number(m, "durationMs"))
                .build();
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte x : d) {
                hex.append(String.format("%02x", x));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return toJson();
    }

    private static void field(StringBuilder j, String key, String value) {
        if (j.length() > 1) {
            j.append(',');
        }
        j.append('"').append(key).append("\":");
        if (value == null) {
            j.append("null");
        } else {
            quote(j, value);
        }
    }

    private static void array(StringBuilder j, String key, List<String> values) {
        j.append(",\"").append(key).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                j.append(',');
            }
            quote(j, values.get(i));
        }
        j.append(']');
    }

    private static void quote(StringBuilder j, String s) {
        j.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                j.append('\\').append(c);
            } else if (c < 0x20) {
                j.append(String.format("\\u%04x", (int) c));
            } else {
                j.append(c);
            }
        }
        j.append('"');
    }

    private static String string(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v != null && !(v instanceof String)) {
            throw new IllegalArgumentException(key + " is not a string");
        }
        return (String) v;
    }

    private static long number(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Double)) {
            throw new IllegalArgumentException(key + " is not a number");
        }
        return ((Double) v).longValue();
    }

    private static List<String> strings(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List)) {
            throw new IllegalArgumentException(key + " is not an array");
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof String)) {
                throw new IllegalArgumentException(key + " has a non-string entry");
            }
            out.add((String) o);
        }
        return out;
    }

    private static String require(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("evidence needs " + name);
        }
        return value;
    }

    public static final class Builder {
        private String sessionId;
        private String workflow;
        private String outcome;
        private String gateStatus;
        private float passiveScore = Float.NaN;
        private boolean activeVerificationPerformed;
        private List<String> challenges = Collections.emptyList();
        private int attempts;
        private List<String> attemptFailures = Collections.emptyList();
        private String engine;
        private String modelPackage;
        private String captureSha256;
        private String subjectSha256;
        private String stationId;
        private long issuedAt;
        private long durationMs;

        private Builder() {
        }

        public Builder sessionId(String v) { sessionId = v; return this; }
        public Builder workflow(String v) { workflow = v; return this; }
        public Builder outcome(String v) { outcome = v; return this; }
        public Builder gateStatus(String v) { gateStatus = v; return this; }
        public Builder passiveScore(float v) { passiveScore = v; return this; }
        public Builder activeVerificationPerformed(boolean v) { activeVerificationPerformed = v; return this; }
        public Builder challenges(List<String> v) { challenges = v; return this; }
        public Builder attempts(int v) { attempts = v; return this; }
        public Builder attemptFailures(List<String> v) { attemptFailures = v; return this; }
        public Builder engine(String v) { engine = v; return this; }
        public Builder modelPackage(String v) { modelPackage = v; return this; }
        public Builder captureSha256(String v) { captureSha256 = v; return this; }
        public Builder subjectSha256(String v) { subjectSha256 = v; return this; }
        public Builder stationId(String v) { stationId = v; return this; }
        public Builder issuedAt(long v) { issuedAt = v; return this; }
        public Builder durationMs(long v) { durationMs = v; return this; }

        public LivenessEvidence build() {
            return new LivenessEvidence(this);
        }
    }
}
