package io.mosip.registration.liveness.config;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.challenge.ChallengeType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * Liveness configuration for all workflows, read from Registration Client properties.
 *
 * <p>Every key can be set globally ({@code mosip.registration.liveness.passive.threshold=70})
 * and overridden per workflow ({@code mosip.registration.liveness.supervisor.passive.threshold=80}).
 * Unknown keys under the prefix are rejected so that a typo cannot silently weaken the policy.
 * The configuration is local, so it is enforced identically online and offline.
 */
public final class LivenessConfig {

    public static final String PREFIX = "mosip.registration.liveness.";

    private static final Map<String, BiConsumer<LivenessPolicy.Builder, String>> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("enabled", (b, v) -> b.enabled(parseBoolean(v)));
        KEYS.put("passive.threshold", (b, v) -> b.passiveThreshold(parseInt(v)));
        KEYS.put("passive.minFrames", (b, v) -> b.passiveMinFrames(parseInt(v)));
        KEYS.put("passive.timeoutMs", (b, v) -> b.passiveTimeoutMs(parseLong(v)));
        KEYS.put("face.minQuality", (b, v) -> b.minFaceQuality(parseInt(v)));
        KEYS.put("pad.enabled", (b, v) -> b.padEnabled(parseBoolean(v)));
        KEYS.put("pad.minAttackFrames", (b, v) -> b.padMinAttackFrames(parseInt(v)));
        KEYS.put("active.enabled", (b, v) -> b.activeEnabled(parseBoolean(v)));
        KEYS.put("active.always", (b, v) -> b.activeAlways(parseBoolean(v)));
        KEYS.put("active.challenges", (b, v) -> b.challengeTypes(parseChallenges(v)));
        KEYS.put("active.minChallenges", (b, v) -> b.minChallenges(parseInt(v)));
        KEYS.put("active.maxChallenges", (b, v) -> b.maxChallenges(parseInt(v)));
        KEYS.put("active.challengeTimeoutMs", (b, v) -> b.challengeTimeoutMs(parseLong(v)));
        KEYS.put("active.minLivenessScore", (b, v) -> b.activeMinLivenessScore(parseInt(v)));
        KEYS.put("active.headYawDegrees", (b, v) -> b.headYawDegrees(parseFloat(v)));
        KEYS.put("active.headPitchDegrees", (b, v) -> b.headPitchDegrees(parseFloat(v)));
        KEYS.put("retry.max", (b, v) -> b.maxRetries(parseInt(v)));
        KEYS.put("retry.exhaustedAction", (b, v) -> b.retryExhaustedAction(
                FailureAction.valueOf(v.trim().toUpperCase(Locale.ROOT))));
        KEYS.put("capture.holdStillMs", (b, v) -> b.holdStillMs(parseLong(v)));
        KEYS.put("capture.area.enabled", (b, v) -> b.captureAreaEnabled(parseBoolean(v)));
        KEYS.put("capture.area.centerX", (b, v) -> b.captureAreaCenterX(parseFloat(v)));
        KEYS.put("capture.area.centerY", (b, v) -> b.captureAreaCenterY(parseFloat(v)));
        KEYS.put("capture.area.radiusX", (b, v) -> b.captureAreaRadiusX(parseFloat(v)));
        KEYS.put("capture.area.radiusY", (b, v) -> b.captureAreaRadiusY(parseFloat(v)));
        KEYS.put("capture.area.centerTolerance", (b, v) -> b.captureAreaCenterTolerance(parseFloat(v)));
        KEYS.put("capture.area.minFill", (b, v) -> b.captureAreaMinFill(parseFloat(v)));
        KEYS.put("capture.area.maxFill", (b, v) -> b.captureAreaMaxFill(parseFloat(v)));
        KEYS.put("occlusion.enabled", (b, v) -> b.occlusionEnabled(parseBoolean(v)));
        KEYS.put("occlusion.graceMs", (b, v) -> b.occlusionGraceMs(parseLong(v)));
        KEYS.put("occlusion.allowCapture", (b, v) -> b.occlusionAllowCapture(parseBoolean(v)));
        KEYS.put("rateLimit.enabled", (b, v) -> b.rateLimitEnabled(parseBoolean(v)));
        KEYS.put("rateLimit.maxFailures", (b, v) -> b.rateLimitMaxFailures(parseInt(v)));
        KEYS.put("rateLimit.windowMs", (b, v) -> b.rateLimitWindowMs(parseLong(v)));
        KEYS.put("rateLimit.lockoutMs", (b, v) -> b.rateLimitLockoutMs(parseLong(v)));
        KEYS.put("consent.required", (b, v) -> b.consentRequired(parseBoolean(v)));
        KEYS.put("consent.maxAgeMs", (b, v) -> b.consentMaxAgeMs(parseLong(v)));
        KEYS.put("capture.bind.enabled", (b, v) -> b.captureBindEnabled(parseBoolean(v)));
        KEYS.put("capture.bind.timeoutMs", (b, v) -> b.captureBindTimeoutMs(parseLong(v)));
        KEYS.put("capture.bind.maxFaceShift", (b, v) -> b.captureBindMaxFaceShift(parseFloat(v)));
        KEYS.put("injection.enabled", (b, v) -> b.injectionEnabled(parseBoolean(v)));
        KEYS.put("injection.virtualCameras", (b, v) -> b.virtualCameraPolicy(
                VirtualCameraPolicy.valueOf(v.trim().toUpperCase(Locale.ROOT))));
    }

    private final Map<LivenessWorkflow, LivenessPolicy> policies;

    private LivenessConfig(Map<LivenessWorkflow, LivenessPolicy> policies) {
        this.policies = Collections.unmodifiableMap(new EnumMap<>(policies));
    }

    /** Built-in defaults for every workflow. */
    public static LivenessConfig defaults() {
        return fromProperties(new Properties());
    }

    /** Uses one policy for every workflow (handy for tests and embedding). */
    public static LivenessConfig uniform(LivenessPolicy policy) {
        Map<LivenessWorkflow, LivenessPolicy> map = new EnumMap<>(LivenessWorkflow.class);
        for (LivenessWorkflow workflow : LivenessWorkflow.values()) {
            map.put(workflow, policy);
        }
        return new LivenessConfig(map);
    }

    public static LivenessConfig load(Path file) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new ConfigException("Cannot read liveness configuration " + file, e);
        }
        return fromProperties(props);
    }

    public static LivenessConfig fromProperties(Properties props) {
        rejectUnknownKeys(props);
        Map<LivenessWorkflow, LivenessPolicy> map = new EnumMap<>(LivenessWorkflow.class);
        for (LivenessWorkflow workflow : LivenessWorkflow.values()) {
            LivenessPolicy.Builder builder = LivenessPolicy.builder();
            for (Map.Entry<String, BiConsumer<LivenessPolicy.Builder, String>> key : KEYS.entrySet()) {
                String workflowKey = PREFIX + workflow.configKey() + "." + key.getKey();
                String globalKey = PREFIX + key.getKey();
                String usedKey = props.getProperty(workflowKey) != null ? workflowKey : globalKey;
                String value = props.getProperty(usedKey);
                if (value == null) {
                    continue;
                }
                try {
                    key.getValue().accept(builder, value.trim());
                } catch (IllegalArgumentException e) {
                    throw new ConfigException("Invalid value '" + value + "' for " + usedKey, e);
                }
            }
            try {
                map.put(workflow, builder.build());
            } catch (IllegalArgumentException e) {
                throw new ConfigException("Invalid liveness policy for " + workflow + ": " + e.getMessage(), e);
            }
        }
        return new LivenessConfig(map);
    }

    public LivenessPolicy policyFor(LivenessWorkflow workflow) {
        return policies.get(workflow);
    }

    private static void rejectUnknownKeys(Properties props) {
        TreeSet<String> unknown = new TreeSet<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(PREFIX)) {
                continue;
            }
            String rest = name.substring(PREFIX.length());
            if (!KEYS.containsKey(rest) && !isWorkflowKey(rest)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ConfigException("Unknown liveness configuration key(s): " + unknown);
        }
    }

    private static boolean isWorkflowKey(String rest) {
        for (LivenessWorkflow workflow : LivenessWorkflow.values()) {
            String prefix = workflow.configKey() + ".";
            if (rest.startsWith(prefix) && KEYS.containsKey(rest.substring(prefix.length()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean parseBoolean(String v) {
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v)) {
            return false;
        }
        throw new IllegalArgumentException("expected true or false");
    }

    private static int parseInt(String v) {
        return Integer.parseInt(v);
    }

    private static long parseLong(String v) {
        return Long.parseLong(v);
    }

    private static float parseFloat(String v) {
        return Float.parseFloat(v);
    }

    private static List<ChallengeType> parseChallenges(String v) {
        List<ChallengeType> types = new ArrayList<>();
        for (String part : v.split(",")) {
            if (!part.trim().isEmpty()) {
                types.add(ChallengeType.parse(part));
            }
        }
        return types;
    }
}
