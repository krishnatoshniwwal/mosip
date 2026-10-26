package io.mosip.registration.liveness.regclient;

import io.mosip.registration.liveness.config.LivenessConfig;

import java.util.Map;
import java.util.Properties;

/**
 * Reads the liveness configuration from the Registration Client's global parameters
 * ({@code ApplicationContext.map()}). Those are synced from the server's config
 * ({@code registration-default.properties}) over the client's authenticated sync and stored in the local
 * database, so an online sync delivers new values and an offline client keeps enforcing the last ones it got.
 */
public final class RegClientLivenessConfig {

    private RegClientLivenessConfig() {
    }

    /**
     * @param applicationMap the client's global parameters; keys outside {@code mosip.registration.liveness.} are ignored
     * @throws io.mosip.registration.liveness.config.ConfigException on an unknown key or invalid value, so a
     *         mistyped server setting stops the client instead of silently weakening the policy
     */
    public static LivenessConfig fromApplicationMap(Map<String, ?> applicationMap) {
        return fromApplicationMap(applicationMap, new Properties());
    }

    /** As {@link #fromApplicationMap(Map)}, with {@code base} (e.g. a bundled properties file) for keys the map lacks. */
    public static LivenessConfig fromApplicationMap(Map<String, ?> applicationMap, Properties base) {
        Properties props = new Properties();
        props.putAll(base);
        props.putAll(livenessProperties(applicationMap));
        return LivenessConfig.fromProperties(props);
    }

    /** The {@code mosip.registration.liveness.*} entries of the map, as strings. */
    public static Properties livenessProperties(Map<String, ?> applicationMap) {
        Properties props = new Properties();
        for (Map.Entry<String, ?> e : applicationMap.entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith(LivenessConfig.PREFIX) && e.getValue() != null) {
                props.setProperty(e.getKey(), e.getValue().toString().trim());
            }
        }
        return props;
    }
}
