package io.mosip.registration.liveness.i18n;

import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.session.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivenessMessagesTest {

    @Test
    void everyBundleTranslatesEveryMessageAndNothingElse() {
        Set<String> expected = new HashSet<>(LivenessMessages.uiKeys().keySet());
        for (UserMessage m : UserMessage.values()) {
            expected.add(m.key());
        }
        for (String language : LivenessMessages.LANGUAGES) {
            if ("en".equals(language)) {
                continue;
            }
            LivenessMessages messages = LivenessMessages.forLanguage(language);
            assertEquals(language, messages.language(), "bundle for " + language + " must load");
            assertEquals(expected, messages.bundleKeys(), "keys in messages_" + language + ".properties");
            for (UserMessage m : UserMessage.values()) {
                assertFalse(messages.text(m).trim().isEmpty(), language + " " + m);
            }
        }
    }

    @Test
    void challengePromptsAreActuallyTranslated() {
        for (String language : LivenessMessages.LANGUAGES) {
            if ("en".equals(language)) {
                continue;
            }
            LivenessMessages messages = LivenessMessages.forLanguage(language);
            for (ChallengeType type : ChallengeType.values()) {
                UserMessage prompt = UserMessage.forChallenge(type);
                assertNotEquals(prompt.defaultText(), messages.text(prompt), language + " " + type);
            }
        }
    }

    @Test
    void acceptsMosipThreeLetterCodesAndLanguageTags() {
        assertEquals("fr", LivenessMessages.forLanguage("fra").language());
        assertEquals("ar", LivenessMessages.forLanguage("ara").language());
        assertEquals("ta", LivenessMessages.forLanguage("tam").language());
        assertEquals("fil", LivenessMessages.forLanguage("tgl").language());
        assertEquals("fr", LivenessMessages.forLanguage("fr_TG").language());
        assertEquals("Veuillez cligner des yeux", LivenessMessages.forLanguage("fra").text(UserMessage.CHALLENGE_BLINK));
        assertTrue(LivenessMessages.isSupported("kan"));
    }

    @Test
    void unknownLanguageFallsBackToEnglish() {
        LivenessMessages messages = LivenessMessages.forLanguage("xyz");
        assertEquals("en", messages.language());
        assertEquals(UserMessage.CHALLENGE_SMILE.defaultText(), messages.text(UserMessage.CHALLENGE_SMILE));
        assertFalse(LivenessMessages.isSupported("xyz"));
    }

    @Test
    void arabicIsRightToLeft() {
        assertTrue(LivenessMessages.forLanguage("ara").isRightToLeft());
        assertFalse(LivenessMessages.forLanguage("fra").isRightToLeft());
    }

    @Test
    void uiLabelsAreFormatted() {
        assertEquals("Attempt 2 of 3", LivenessMessages.english().ui(LivenessMessages.UI_ATTEMPT, 2, 3));
        assertEquals("Tentative 2 sur 3", LivenessMessages.forLanguage("fr").ui(LivenessMessages.UI_ATTEMPT, 2, 3));
        assertEquals("Bloqué jusqu'à 10:30", LivenessMessages.forLanguage("fr").ui(LivenessMessages.UI_LOCKED_UNTIL, "10:30"),
                "apostrophes survive MessageFormat");
    }

    @Test
    void securityMessagesStayGenericInEveryLanguage() {
        // PAD failures share the generic failure text; the attack type is never part of any message.
        for (String language : LivenessMessages.LANGUAGES) {
            LivenessMessages messages = LivenessMessages.forLanguage(language);
            assertEquals(messages.text(UserMessage.FAIL_GENERIC),
                    messages.text(io.mosip.registration.liveness.session.FailureReason.PAD_ATTACK_DETECTED.message()));
        }
    }
}
