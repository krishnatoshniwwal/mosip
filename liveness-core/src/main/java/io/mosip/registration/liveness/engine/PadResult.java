package io.mosip.registration.liveness.engine;

/** Per-frame Presentation Attack Detection outcome. */
public final class PadResult {

    private static final PadResult NOT_EVALUATED = new PadResult(false, AttackType.NONE, Float.NaN);

    private final boolean attackDetected;
    private final AttackType attackType;
    private final float bonaFideScore;

    private PadResult(boolean attackDetected, AttackType attackType, float bonaFideScore) {
        this.attackDetected = attackDetected;
        this.attackType = attackType;
        this.bonaFideScore = bonaFideScore;
    }

    public static PadResult bonaFide(float bonaFideScore) {
        return new PadResult(false, AttackType.NONE, bonaFideScore);
    }

    public static PadResult attack(AttackType type, float bonaFideScore) {
        return new PadResult(true, type == null ? AttackType.UNKNOWN : type, bonaFideScore);
    }

    /** For frames with no face, or engines without PAD. */
    public static PadResult notEvaluated() {
        return NOT_EVALUATED;
    }

    public boolean isAttackDetected() { return attackDetected; }
    public AttackType getAttackType() { return attackType; }
    /** Engine confidence (0..100) that the presentation is bona fide; NaN if not evaluated. */
    public float getBonaFideScore() { return bonaFideScore; }
}
