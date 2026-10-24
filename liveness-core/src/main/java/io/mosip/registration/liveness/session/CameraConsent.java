package io.mosip.registration.liveness.session;

/**
 * The person's answer to the camera permission notice, given before the device is opened. The
 * capture screen shows the notice ({@code LivenessMessages.UI_CONSENT_TEXT}), and records the answer
 * with the notice version and language it was shown in. The service refuses to open the camera when
 * the policy requires consent and the answer is missing, negative or too old.
 *
 * <p>Holds no personal data: who consented is already known to the workflow (operator login,
 * registration ID), and the audit trail ties the answer to the session.
 */
public final class CameraConsent {

    private final boolean granted;
    private final long answeredAt;
    private final String noticeVersion;
    private final String language;

    private CameraConsent(boolean granted, long answeredAt, String noticeVersion, String language) {
        this.granted = granted;
        this.answeredAt = answeredAt;
        this.noticeVersion = noticeVersion;
        this.language = language;
    }

    /**
     * @param noticeVersion identifies the exact notice text shown, so a changed notice can be traced
     * @param language      language the notice was shown in
     * @param answeredAt    epoch milliseconds, from the same clock as the session
     */
    public static CameraConsent granted(String noticeVersion, String language, long answeredAt) {
        return new CameraConsent(true, answeredAt, noticeVersion, language);
    }

    public static CameraConsent declined(String noticeVersion, String language, long answeredAt) {
        return new CameraConsent(false, answeredAt, noticeVersion, language);
    }

    public boolean isGranted() { return granted; }
    public long getAnsweredAt() { return answeredAt; }
    public String getNoticeVersion() { return noticeVersion; }
    public String getLanguage() { return language; }
}
