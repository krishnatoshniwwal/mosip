package io.mosip.registration.liveness.evidence;

/** Result of {@link LivenessEvidenceJws#verify}. Only {@link Status#ACCEPTED} means the face was liveness-verified. */
public final class EvidenceVerification {

    public enum Status {
        /** Signed by the station, liveness passed, and the evidence names this exact capture. */
        ACCEPTED,
        /** Genuine evidence that liveness did not pass (e.g. sent to supervisor review): route to manual adjudication. */
        LIVENESS_NOT_PASSED,
        /** Genuine evidence for a different face: the capture was swapped after liveness. */
        CAPTURE_MISMATCH,
        /** Genuine evidence for a different registration or user. */
        SUBJECT_MISMATCH,
        /** Issued in the future by the server's clock. */
        NOT_YET_VALID,
        /** Not signed by this station's key, or tampered with. */
        BAD_SIGNATURE,
        /** Not a liveness evidence token. */
        MALFORMED
    }

    private final Status status;
    private final LivenessEvidence evidence;
    private final String detail;

    private EvidenceVerification(Status status, LivenessEvidence evidence, String detail) {
        this.status = status;
        this.evidence = evidence;
        this.detail = detail;
    }

    static EvidenceVerification of(Status status, LivenessEvidence evidence, String detail) {
        return new EvidenceVerification(status, evidence, detail);
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }

    public Status getStatus() { return status; }
    /** The signed evidence, once the signature verified; null for BAD_SIGNATURE and MALFORMED. */
    public LivenessEvidence getEvidence() { return evidence; }
    public String getDetail() { return detail; }

    @Override
    public String toString() {
        return status + (detail == null ? "" : ": " + detail);
    }
}
