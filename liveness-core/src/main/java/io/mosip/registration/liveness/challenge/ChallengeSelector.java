package io.mosip.registration.liveness.challenge;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Picks the challenge sequence for an attempt. Both the number of challenges (between min and max)
 * and their order come from a {@link SecureRandom}, there are no repeats within a sequence, and a
 * retry gets a different sequence whenever the pool allows. The user never chooses.
 */
public final class ChallengeSelector {

    private static final int RESHUFFLES = 5;

    private final Random random;

    public ChallengeSelector() {
        this(new SecureRandom());
    }

    /** A seeded {@link Random} makes sequences reproducible; use only in tests. */
    public ChallengeSelector(Random random) {
        this.random = random;
    }

    /**
     * @param allowed   challenge types allowed by policy
     * @param supported challenge types the liveness engine can verify
     * @param previous  sequence used in the previous attempt, avoided where possible
     * @throws IllegalStateException if fewer than {@code min} challenges are both allowed and supported
     */
    public List<ChallengeType> select(List<ChallengeType> allowed, int min, int max,
                                      Set<ChallengeType> supported, List<ChallengeType> previous) {
        List<ChallengeType> pool = new ArrayList<>();
        for (ChallengeType type : allowed) {
            if (supported.contains(type) && !pool.contains(type)) {
                pool.add(type);
            }
        }
        if (pool.size() < min || pool.isEmpty()) {
            throw new IllegalStateException("Only " + pool.size() + " configured challenge type(s) are supported"
                    + " by the liveness engine; at least " + Math.max(min, 1) + " required");
        }
        int upper = Math.min(max, pool.size());
        List<ChallengeType> result = null;
        for (int i = 0; i < RESHUFFLES; i++) {
            int count = min + random.nextInt(upper - min + 1);
            Collections.shuffle(pool, random);
            result = new ArrayList<>(pool.subList(0, count));
            if (!result.equals(previous)) {
                break;
            }
        }
        return Collections.unmodifiableList(result);
    }
}
