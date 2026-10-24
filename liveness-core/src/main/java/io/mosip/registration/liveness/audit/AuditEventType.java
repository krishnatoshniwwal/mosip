package io.mosip.registration.liveness.audit;

/** Liveness audit events, with stable codes for the Registration Client audit trail. */
public enum AuditEventType {
    SESSION_STARTED("REG-LIV-001"),
    ATTEMPT_STARTED("REG-LIV-002"),
    PASSIVE_RESULT("REG-LIV-003"),
    CHALLENGE_ISSUED("REG-LIV-004"),
    CHALLENGE_PASSED("REG-LIV-005"),
    CHALLENGE_FAILED("REG-LIV-006"),
    PAD_ATTACK_DETECTED("REG-LIV-007"),
    ATTEMPT_FAILED("REG-LIV-008"),
    LIVENESS_PASSED("REG-LIV-009"),
    LIVENESS_FAILED("REG-LIV-010"),
    SESSION_CANCELLED("REG-LIV-011"),
    SYSTEM_ERROR("REG-LIV-012"),
    OCCLUSION_FALLBACK("REG-LIV-013"),
    RATE_LIMIT_LOCKOUT("REG-LIV-014"),
    CONSENT_GRANTED("REG-LIV-015"),
    CONSENT_NOT_GIVEN("REG-LIV-016");

    private final String code;

    AuditEventType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
