package io.mosip.registration.liveness.engine;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Per-frame Presentation Attack Detection outcome. */
public final class PadResult {

    private static final PadResult NOT_EVALUATED =
            new PadResult(false, AttackType.NONE, Float.NaN, Collections.emptySet());

    private final boolean attackDetected;
    private final AttackType attackType;
    private final float bonaFideScore;
    private final Set<PadIndicator> indicators;

    private PadResult(boolean attackDetected, AttackType attackType, float bonaFideScore, Set<PadIndicator> indicators) {
        this.attackDetected = attackDetected;
        this.attackType = attackType;
        this.bonaFideScore = bonaFideScore;
        this.indicators = indicators.isEmpty() ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(indicators));
    }

    public static PadResult bonaFide(float bonaFideScore) {
        return bonaFide(bonaFideScore, Collections.emptySet());
    }

    /** Bona fide overall, but with weak attack cues that lowered the score. */
    public static PadResult bonaFide(float bonaFideScore, Set<PadIndicator> indicators) {
        return new PadResult(false, AttackType.NONE, bonaFideScore, indicators);
    }

    public static PadResult attack(AttackType type, float bonaFideScore) {
        return attack(type, bonaFideScore, Collections.emptySet());
    }

    public static PadResult attack(AttackType type, float bonaFideScore, Set<PadIndicator> indicators) {
        return new PadResult(true, type == null ? AttackType.UNKNOWN : type, bonaFideScore, indicators);
    }

    /** For frames with no face, or engines without PAD. */
    public static PadResult notEvaluated() {
        return NOT_EVALUATED;
    }

    public boolean isAttackDetected() { return attackDetected; }
    public AttackType getAttackType() { return attackType; }
    /** Engine confidence (0..100) that the presentation is bona fide; NaN if not evaluated. */
    public float getBonaFideScore() { return bonaFideScore; }
    /** Attack cues observed in this frame (for audit only); empty if none or not reported by the engine. */
    public Set<PadIndicator> getIndicators() { return indicators; }
}
