package io.mosip.registration.liveness.i18n;

import io.mosip.registration.liveness.session.UserMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Translated liveness prompts and status text. Look up a language once and use it for the whole
 * capture screen:
 *
 * <pre>
 * LivenessMessages messages = LivenessMessages.forLanguage("fra");   // MOSIP ISO 639-3, or "fr"
 * label.setText(messages.text(update.getMessage()));
 * </pre>
 *
 * Bundles are UTF-8 files {@code messages_<lang>.properties} next to this class, loaded through a
 * Reader so they behave the same on the desktop JDK and on Android. A key missing from a bundle falls
 * back to the English text, so a partial translation never shows a blank prompt. Translations other
 * than English must be reviewed by native speakers before deployment.
 */
public final class LivenessMessages {

    private static final Logger LOG = Logger.getLogger(LivenessMessages.class.getName());
    private static final String BUNDLE = "messages_%s.properties";

    /** Bundled languages (ISO 639-1, or 639-2 where no 639-1 code exists). */
    public static final List<String> LANGUAGES = Collections.unmodifiableList(
            Arrays.asList("en", "fr", "ar", "hi", "kn", "ta", "fil", "am", "si"));

    public static final String UI_START = "liveness.ui.start";
    public static final String UI_RETRY = "liveness.ui.retry";
    public static final String UI_CANCEL = "liveness.ui.cancel";
    /** Args: attempt number, maximum attempts. */
    public static final String UI_ATTEMPT = "liveness.ui.attempt";
    /** Args: challenge number, challenge count. */
    public static final String UI_CHALLENGE = "liveness.ui.challenge";
    /** Args: seconds left. */
    public static final String UI_TIME_LEFT = "liveness.ui.timeLeft";
    public static final String UI_PRESS_RETRY = "liveness.ui.pressRetry";
    /** Args: time the lockout ends, already formatted. */
    public static final String UI_LOCKED_UNTIL = "liveness.ui.lockedUntil";
    public static final String UI_CONSENT_TITLE = "liveness.ui.consentTitle";
    /** The camera notice. Its wording is versioned by {@link #CONSENT_NOTICE_VERSION}. */
    public static final String UI_CONSENT_TEXT = "liveness.ui.consentText";
    public static final String UI_ALLOW = "liveness.ui.allow";
    public static final String UI_DENY = "liveness.ui.deny";
    /** Bump whenever the consent notice wording changes in any language; recorded with every consent. */
    public static final String CONSENT_NOTICE_VERSION = "camera-notice-v1";

    private static final Map<String, String> UI_ENGLISH = new LinkedHashMap<>();
    private static final Map<String, String> MOSIP_CODES = new HashMap<>();
    private static final List<String> RIGHT_TO_LEFT = Arrays.asList("ar", "fa", "he", "ur");
    private static final Map<String, LivenessMessages> CACHE = new ConcurrentHashMap<>();

    static {
        UI_ENGLISH.put(UI_START, "Start");
        UI_ENGLISH.put(UI_RETRY, "Retry");
        UI_ENGLISH.put(UI_CANCEL, "Cancel");
        UI_ENGLISH.put(UI_ATTEMPT, "Attempt {0} of {1}");
        UI_ENGLISH.put(UI_CHALLENGE, "Challenge {0} of {1}");
        UI_ENGLISH.put(UI_TIME_LEFT, "Time left: {0} s");
        UI_ENGLISH.put(UI_PRESS_RETRY, "Press \"Retry\" to try again.");
        UI_ENGLISH.put(UI_LOCKED_UNTIL, "Locked until {0}");
        UI_ENGLISH.put(UI_CONSENT_TITLE, "Camera permission");
        UI_ENGLISH.put(UI_CONSENT_TEXT, "To capture your face and confirm that you are present in person, this application "
                + "needs to turn on the camera. Images are processed on this device and are not sent anywhere for the "
                + "liveness check. Do you allow the camera to be used?");
        UI_ENGLISH.put(UI_ALLOW, "Allow");
        UI_ENGLISH.put(UI_DENY, "Don't allow");

        // MOSIP configures languages with ISO 639-3 codes (mosip.mandatory-languages=eng,ara,fra ...).
        MOSIP_CODES.put("eng", "en");
        MOSIP_CODES.put("fra", "fr");
        MOSIP_CODES.put("fre", "fr");
        MOSIP_CODES.put("ara", "ar");
        MOSIP_CODES.put("hin", "hi");
        MOSIP_CODES.put("kan", "kn");
        MOSIP_CODES.put("tam", "ta");
        MOSIP_CODES.put("tgl", "fil");
        MOSIP_CODES.put("amh", "am");
        MOSIP_CODES.put("sin", "si");
    }

    private final String language;
    private final Locale locale;
    private final Properties texts;

    private LivenessMessages(String language, Properties texts) {
        this.language = language;
        this.locale = new Locale(language);
        this.texts = texts;
    }

    public static LivenessMessages english() {
        return forLanguage("en");
    }

    /**
     * @param code ISO 639-1 ({@code fr}), ISO 639-3 as used by MOSIP ({@code fra}) or a language tag
     *             ({@code fr-TG}); an unsupported language falls back to English
     */
    public static LivenessMessages forLanguage(String code) {
        String language = normalise(code);
        if (!LANGUAGES.contains(language)) {
            LOG.warning("No liveness messages for language '" + code + "'; using English");
            language = "en";
        }
        return CACHE.computeIfAbsent(language, l -> new LivenessMessages(l, load(l)));
    }

    /** True if {@code code} names a bundled language (in any form accepted by {@link #forLanguage}). */
    public static boolean isSupported(String code) {
        return LANGUAGES.contains(normalise(code));
    }

    static String normalise(String code) {
        if (code == null) {
            return "en";
        }
        String lower = code.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = lower.indexOf('-');
        String base = dash > 0 ? lower.substring(0, dash) : lower;
        String mapped = MOSIP_CODES.get(base);
        return mapped != null ? mapped : base;
    }

    private static Properties load(String language) {
        Properties props = new Properties();
        if ("en".equals(language)) {
            return props;
        }
        String name = String.format(BUNDLE, language);
        try (InputStream in = LivenessMessages.class.getResourceAsStream(name)) {
            if (in == null) {
                LOG.warning("Liveness message bundle " + name + " is missing from the classpath; using English");
                return props;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read liveness message bundle " + name + "; using English", e);
        }
        return props;
    }

    /** ISO code of the language actually used (after fallback). */
    public String language() {
        return language;
    }

    public Locale locale() {
        return locale;
    }

    /** Language name in that language, for a language picker. */
    public String displayName() {
        String name = locale.getDisplayLanguage(locale);
        return name.isEmpty() ? language : name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    /** Whether the UI should lay text out right to left. */
    public boolean isRightToLeft() {
        return RIGHT_TO_LEFT.contains(language);
    }

    public String text(UserMessage message) {
        return texts.getProperty(message.key(), message.defaultText());
    }

    /** Text for capture-screen labels ({@code UI_*} keys), with {@link MessageFormat} arguments. */
    public String ui(String key, Object... args) {
        String english = UI_ENGLISH.get(key);
        if (english == null) {
            throw new IllegalArgumentException("Unknown UI message key " + key);
        }
        String pattern = texts.getProperty(key, english);
        return args.length == 0 ? pattern : new MessageFormat(pattern, locale).format(args);
    }

    /** Every UI key with its English text; used by tests to check bundles are complete. */
    public static Map<String, String> uiKeys() {
        return Collections.unmodifiableMap(UI_ENGLISH);
    }

    /** Whether this bundle translates {@code key} (rather than falling back to English). */
    public boolean hasTranslation(String key) {
        return "en".equals(language) || texts.containsKey(key);
    }

    /** Keys present in this bundle; used by tests to reject stale or misspelt keys. */
    public Set<String> bundleKeys() {
        return texts.stringPropertyNames();
    }
}
