package io.mosip.registration.liveness.challenge;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeSelectorTest {

    private static final List<ChallengeType> ALLOWED = Arrays.asList(
            ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT);
    private static final Set<ChallengeType> ALL = EnumSet.allOf(ChallengeType.class);

    @Test
    void selectsDistinctChallengesWithinCountBounds() {
        ChallengeSelector selector = new ChallengeSelector(new Random(1));
        for (int i = 0; i < 200; i++) {
            List<ChallengeType> picked = selector.select(ALLOWED, 2, 3, ALL, Collections.emptyList());
            assertTrue(picked.size() >= 2 && picked.size() <= 3);
            assertEquals(picked.size(), new HashSet<>(picked).size());
            assertTrue(ALLOWED.containsAll(picked));
        }
    }

    @Test
    void sequencesAreUnpredictable() {
        ChallengeSelector selector = new ChallengeSelector(new Random(2));
        Set<List<ChallengeType>> seen = new HashSet<>();
        Set<ChallengeType> firsts = EnumSet.noneOf(ChallengeType.class);
        for (int i = 0; i < 200; i++) {
            List<ChallengeType> picked = selector.select(ALLOWED, 2, 3, ALL, Collections.emptyList());
            seen.add(picked);
            firsts.add(picked.get(0));
        }
        assertTrue(seen.size() > 20, "expected many distinct sequences, got " + seen.size());
        assertEquals(new HashSet<>(ALLOWED), firsts, "every challenge should be able to come first");
    }

    @Test
    void onlyIssuesChallengesTheEngineSupports() {
        ChallengeSelector selector = new ChallengeSelector(new Random(3));
        Set<ChallengeType> supported = EnumSet.of(ChallengeType.BLINK, ChallengeType.TURN_LEFT);
        for (int i = 0; i < 50; i++) {
            assertTrue(supported.containsAll(selector.select(ALLOWED, 1, 3, supported, Collections.emptyList())));
        }
    }

    @Test
    void avoidsRepeatingThePreviousSequence() {
        ChallengeSelector selector = new ChallengeSelector(new Random(4));
        List<ChallengeType> previous = Arrays.asList(ChallengeType.SMILE, ChallengeType.BLINK);
        for (int i = 0; i < 100; i++) {
            assertNotEquals(previous, selector.select(ALLOWED, 2, 2, ALL, previous));
        }
    }

    @Test
    void failsWhenTooFewChallengesAreAvailable() {
        ChallengeSelector selector = new ChallengeSelector(new Random(5));
        assertThrows(IllegalStateException.class,
                () -> selector.select(ALLOWED, 2, 3, EnumSet.of(ChallengeType.BLINK), Collections.emptyList()));
    }
}
