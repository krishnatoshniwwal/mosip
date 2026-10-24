package io.mosip.registration.liveness.config;

import io.mosip.registration.liveness.challenge.ChallengeType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Immutable liveness policy for one workflow. Scores and thresholds are on a 0..100 scale.
 * Build with {@link #builder()}; {@link Builder#build()} validates the combination.
 */
public final class LivenessPolicy {

    private final boolean enabled;
    private final int passiveThreshold;
    private final int passiveMinFrames;
    private final long passiveTimeoutMs;
    private final int minFaceQuality;
    private final boolean padEnabled;
    private final int padMinAttackFrames;
    private final boolean activeEnabled;
    private final boolean activeAlways;
    private final List<ChallengeType> challengeTypes;
    private final int minChallenges;
    private final int maxChallenges;
    private final long challengeTimeoutMs;
    private final int activeMinLivenessScore;
    private final float headYawDegrees;
    private final float headPitchDegrees;
    private final int maxRetries;
    private final FailureAction retryExhaustedAction;
    private final long holdStillMs;
    private final CaptureArea captureArea;
    private final boolean occlusionEnabled;
    private final long occlusionGraceMs;
    private final boolean occlusionAllowCapture;
    private final boolean rateLimitEnabled;
    private final int rateLimitMaxFailures;
    private final long rateLimitWindowMs;
    private final long rateLimitLockoutMs;

    private LivenessPolicy(Builder b) {
        enabled = b.enabled;
        passiveThreshold = b.passiveThreshold;
        passiveMinFrames = b.passiveMinFrames;
        passiveTimeoutMs = b.passiveTimeoutMs;
        minFaceQuality = b.minFaceQuality;
        padEnabled = b.padEnabled;
        padMinAttackFrames = b.padMinAttackFrames;
        activeEnabled = b.activeEnabled;
        activeAlways = b.activeAlways;
        challengeTypes = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(b.challengeTypes)));
        minChallenges = b.minChallenges;
        maxChallenges = b.maxChallenges;
        challengeTimeoutMs = b.challengeTimeoutMs;
        activeMinLivenessScore = b.activeMinLivenessScore;
        headYawDegrees = b.headYawDegrees;
        headPitchDegrees = b.headPitchDegrees;
        maxRetries = b.maxRetries;
        retryExhaustedAction = b.retryExhaustedAction;
        holdStillMs = b.holdStillMs;
        captureArea = new CaptureArea(b.areaEnabled, b.areaCenterX, b.areaCenterY, b.areaRadiusX, b.areaRadiusY,
                b.areaCenterTolerance, b.areaMinFill, b.areaMaxFill);
        occlusionEnabled = b.occlusionEnabled;
        occlusionGraceMs = b.occlusionGraceMs;
        occlusionAllowCapture = b.occlusionAllowCapture;
        rateLimitEnabled = b.rateLimitEnabled;
        rateLimitMaxFailures = b.rateLimitMaxFailures;
        rateLimitWindowMs = b.rateLimitWindowMs;
        rateLimitLockoutMs = b.rateLimitLockoutMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .enabled(enabled)
                .passiveThreshold(passiveThreshold)
                .passiveMinFrames(passiveMinFrames)
                .passiveTimeoutMs(passiveTimeoutMs)
                .minFaceQuality(minFaceQuality)
                .padEnabled(padEnabled)
                .padMinAttackFrames(padMinAttackFrames)
                .activeEnabled(activeEnabled)
                .activeAlways(activeAlways)
                .challengeTypes(challengeTypes)
                .minChallenges(minChallenges)
                .maxChallenges(maxChallenges)
                .challengeTimeoutMs(challengeTimeoutMs)
                .activeMinLivenessScore(activeMinLivenessScore)
                .headYawDegrees(headYawDegrees)
                .headPitchDegrees(headPitchDegrees)
                .maxRetries(maxRetries)
                .retryExhaustedAction(retryExhaustedAction)
                .holdStillMs(holdStillMs)
                .captureArea(captureArea)
                .occlusionEnabled(occlusionEnabled)
                .occlusionGraceMs(occlusionGraceMs)
                .occlusionAllowCapture(occlusionAllowCapture)
                .rateLimitEnabled(rateLimitEnabled)
                .rateLimitMaxFailures(rateLimitMaxFailures)
                .rateLimitWindowMs(rateLimitWindowMs)
                .rateLimitLockoutMs(rateLimitLockoutMs);
    }

    /** Master switch. When false, faces are captured without liveness or PAD checks. */
    public boolean isEnabled() { return enabled; }
    /** Passive liveness score (0..100) at or above which no active challenge is needed. */
    public int getPassiveThreshold() { return passiveThreshold; }
    /** Usable frames to aggregate before the passive decision is made. */
    public int getPassiveMinFrames() { return passiveMinFrames; }
    /** Time allowed to collect the passive frames. */
    public long getPassiveTimeoutMs() { return passiveTimeoutMs; }
    /** Minimum face quality (0..100) for a frame to count towards liveness or be captured. */
    public int getMinFaceQuality() { return minFaceQuality; }
    public boolean isPadEnabled() { return padEnabled; }
    /** Frames flagged as an attack within one attempt before the attempt is rejected. */
    public int getPadMinAttackFrames() { return padMinAttackFrames; }
    /** Whether a low passive score escalates to an active challenge (otherwise it fails). */
    public boolean isActiveEnabled() { return activeEnabled; }
    /** Always run active challenges, even when passive liveness is confident. */
    public boolean isActiveAlways() { return activeAlways; }
    public List<ChallengeType> getChallengeTypes() { return challengeTypes; }
    public int getMinChallenges() { return minChallenges; }
    public int getMaxChallenges() { return maxChallenges; }
    public long getChallengeTimeoutMs() { return challengeTimeoutMs; }
    /** Smoothed liveness score that must be held while challenges are performed. */
    public int getActiveMinLivenessScore() { return activeMinLivenessScore; }
    public float getHeadYawDegrees() { return headYawDegrees; }
    public float getHeadPitchDegrees() { return headPitchDegrees; }
    /** Retries allowed after the first failed attempt; total attempts = maxRetries + 1. */
    public int getMaxRetries() { return maxRetries; }
    public FailureAction getRetryExhaustedAction() { return retryExhaustedAction; }
    /** How long the subject must hold still while the final face image is selected. */
    public long getHoldStillMs() { return holdStillMs; }
    /** Oval the face must be inside to count towards liveness and capture; the UI draws the same oval. */
    public CaptureArea getCaptureArea() { return captureArea; }
    /** Whether covered faces (mask, scarf, sunglasses) are detected and handled. */
    public boolean isOcclusionEnabled() { return occlusionEnabled; }
    /**
     * How long the subject is asked to uncover their face before verification falls back to active
     * challenges that avoid the covered region.
     */
    public long getOcclusionGraceMs() { return occlusionGraceMs; }
    /** Whether the final captured image may show a covered face (false for the resident's biometric). */
    public boolean isOcclusionAllowCapture() { return occlusionAllowCapture; }
    /** Whether repeated failures lock face verification for the subject (or station) for a while. */
    public boolean isRateLimitEnabled() { return rateLimitEnabled; }
    /** Failed attempts within {@link #getRateLimitWindowMs()} that trigger a lockout. */
    public int getRateLimitMaxFailures() { return rateLimitMaxFailures; }
    public long getRateLimitWindowMs() { return rateLimitWindowMs; }
    public long getRateLimitLockoutMs() { return rateLimitLockoutMs; }

    /** Compact, non-sensitive description for audit records. */
    public String summary() {
        if (!enabled) {
            return "disabled";
        }
        return "threshold=" + passiveThreshold
                + ",pad=" + padEnabled
                + ",active=" + (activeAlways ? "always" : activeEnabled ? "on-low-score" : "off")
                + ",challenges=" + minChallenges + ".." + maxChallenges + challengeTypes
                + ",maxRetries=" + maxRetries
                + ",area=" + captureArea.isEnabled()
                + ",occlusion=" + (occlusionEnabled ? (occlusionAllowCapture ? "allow-capture" : "block-capture") : "off")
                + ",rateLimit=" + (rateLimitEnabled
                        ? rateLimitMaxFailures + "/" + rateLimitWindowMs + "ms,lock=" + rateLimitLockoutMs + "ms" : "off");
    }

    public static final class Builder {
        private boolean enabled = true;
        private int passiveThreshold = 70;
        private int passiveMinFrames = 8;
        private long passiveTimeoutMs = 10_000;
        private int minFaceQuality = 40;
        private boolean padEnabled = true;
        private int padMinAttackFrames = 2;
        private boolean activeEnabled = true;
        private boolean activeAlways = false;
        private List<ChallengeType> challengeTypes = Arrays.asList(
                ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT);
        private int minChallenges = 2;
        private int maxChallenges = 3;
        private long challengeTimeoutMs = 7_000;
        private int activeMinLivenessScore = 40;
        private float headYawDegrees = 20f;
        private float headPitchDegrees = 15f;
        private int maxRetries = 2;
        private FailureAction retryExhaustedAction = FailureAction.BLOCK;
        private long holdStillMs = 700;
        private boolean areaEnabled = true;
        private float areaCenterX = 0.5f;
        private float areaCenterY = 0.48f;
        private float areaRadiusX = 0.22f;
        private float areaRadiusY = 0.37f;
        private float areaCenterTolerance = 0.45f;
        private float areaMinFill = 0.4f;
        private float areaMaxFill = 1.15f;
        private boolean occlusionEnabled = true;
        private long occlusionGraceMs = 3_000;
        private boolean occlusionAllowCapture = false;
        private boolean rateLimitEnabled = true;
        private int rateLimitMaxFailures = 5;
        private long rateLimitWindowMs = 600_000;
        private long rateLimitLockoutMs = 900_000;

        private Builder() {
        }

        public Builder enabled(boolean v) { enabled = v; return this; }
        public Builder passiveThreshold(int v) { passiveThreshold = v; return this; }
        public Builder passiveMinFrames(int v) { passiveMinFrames = v; return this; }
        public Builder passiveTimeoutMs(long v) { passiveTimeoutMs = v; return this; }
        public Builder minFaceQuality(int v) { minFaceQuality = v; return this; }
        public Builder padEnabled(boolean v) { padEnabled = v; return this; }
        public Builder padMinAttackFrames(int v) { padMinAttackFrames = v; return this; }
        public Builder activeEnabled(boolean v) { activeEnabled = v; return this; }
        public Builder activeAlways(boolean v) { activeAlways = v; return this; }
        public Builder challengeTypes(List<ChallengeType> v) { challengeTypes = new ArrayList<>(v); return this; }
        public Builder minChallenges(int v) { minChallenges = v; return this; }
        public Builder maxChallenges(int v) { maxChallenges = v; return this; }
        public Builder challengeTimeoutMs(long v) { challengeTimeoutMs = v; return this; }
        public Builder activeMinLivenessScore(int v) { activeMinLivenessScore = v; return this; }
        public Builder headYawDegrees(float v) { headYawDegrees = v; return this; }
        public Builder headPitchDegrees(float v) { headPitchDegrees = v; return this; }
        public Builder maxRetries(int v) { maxRetries = v; return this; }
        public Builder retryExhaustedAction(FailureAction v) { retryExhaustedAction = v; return this; }
        public Builder holdStillMs(long v) { holdStillMs = v; return this; }
        public Builder captureAreaEnabled(boolean v) { areaEnabled = v; return this; }
        public Builder captureAreaCenterX(float v) { areaCenterX = v; return this; }
        public Builder captureAreaCenterY(float v) { areaCenterY = v; return this; }
        public Builder captureAreaRadiusX(float v) { areaRadiusX = v; return this; }
        public Builder captureAreaRadiusY(float v) { areaRadiusY = v; return this; }
        public Builder captureAreaCenterTolerance(float v) { areaCenterTolerance = v; return this; }
        public Builder captureAreaMinFill(float v) { areaMinFill = v; return this; }
        public Builder captureAreaMaxFill(float v) { areaMaxFill = v; return this; }
        public Builder captureArea(CaptureArea a) {
            areaEnabled = a.isEnabled();
            areaCenterX = a.getCenterX();
            areaCenterY = a.getCenterY();
            areaRadiusX = a.getRadiusX();
            areaRadiusY = a.getRadiusY();
            areaCenterTolerance = a.getCenterTolerance();
            areaMinFill = a.getMinFill();
            areaMaxFill = a.getMaxFill();
            return this;
        }
        public Builder occlusionEnabled(boolean v) { occlusionEnabled = v; return this; }
        public Builder occlusionGraceMs(long v) { occlusionGraceMs = v; return this; }
        public Builder occlusionAllowCapture(boolean v) { occlusionAllowCapture = v; return this; }
        public Builder rateLimitEnabled(boolean v) { rateLimitEnabled = v; return this; }
        public Builder rateLimitMaxFailures(int v) { rateLimitMaxFailures = v; return this; }
        public Builder rateLimitWindowMs(long v) { rateLimitWindowMs = v; return this; }
        public Builder rateLimitLockoutMs(long v) { rateLimitLockoutMs = v; return this; }

        /** @throws IllegalArgumentException if the combination of values is invalid */
        public LivenessPolicy build() {
            requireScore("passive.threshold", passiveThreshold);
            requireScore("face.minQuality", minFaceQuality);
            requireScore("active.minLivenessScore", activeMinLivenessScore);
            require(passiveMinFrames >= 1, "passive.minFrames must be at least 1");
            require(passiveTimeoutMs > 0, "passive.timeoutMs must be positive");
            require(padMinAttackFrames >= 1, "pad.minAttackFrames must be at least 1");
            require(challengeTimeoutMs > 0, "active.challengeTimeoutMs must be positive");
            require(headYawDegrees > 0 && headYawDegrees <= 60, "active.headYawDegrees must be in (0, 60]");
            require(headPitchDegrees > 0 && headPitchDegrees <= 60, "active.headPitchDegrees must be in (0, 60]");
            require(maxRetries >= 0, "retry.max must not be negative");
            require(holdStillMs >= 0, "capture.holdStillMs must not be negative");
            require(retryExhaustedAction != null, "retry.exhaustedAction is required");
            require(!activeAlways || activeEnabled, "active.always requires active.enabled=true");
            require(occlusionGraceMs >= 0, "occlusion.graceMs must not be negative");
            if (rateLimitEnabled) {
                require(rateLimitMaxFailures >= 1, "rateLimit.maxFailures must be at least 1");
                require(rateLimitWindowMs > 0, "rateLimit.windowMs must be positive");
                require(rateLimitLockoutMs > 0, "rateLimit.lockoutMs must be positive");
            }
            new CaptureArea(areaEnabled, areaCenterX, areaCenterY, areaRadiusX, areaRadiusY,
                    areaCenterTolerance, areaMinFill, areaMaxFill).validate();
            if (activeEnabled) {
                require(challengeTypes != null && !challengeTypes.isEmpty(),
                        "active.challenges must list at least one challenge when active liveness is enabled");
                require(minChallenges >= 1, "active.minChallenges must be at least 1");
                require(maxChallenges >= minChallenges, "active.maxChallenges must be >= active.minChallenges");
                require(minChallenges <= new LinkedHashSet<>(challengeTypes).size(),
                        "active.minChallenges exceeds the number of distinct challenge types");
            }
            return new LivenessPolicy(this);
        }

        private static void requireScore(String key, int value) {
            require(value >= 0 && value <= 100, key + " must be between 0 and 100");
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalArgumentException(message);
            }
        }
    }
}
