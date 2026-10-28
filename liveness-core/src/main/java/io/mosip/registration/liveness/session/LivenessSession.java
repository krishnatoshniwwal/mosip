package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ActionDetector;
import io.mosip.registration.liveness.challenge.ActionDetectors;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.challenge.FaceBaseline;
import io.mosip.registration.liveness.config.CaptureArea;
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.config.VirtualCameraPolicy;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.AttackType;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.FaceRegion;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.injection.CameraEndpoint;
import io.mosip.registration.liveness.injection.CameraSourceReport;
import io.mosip.registration.liveness.injection.InjectionCue;
import io.mosip.registration.liveness.injection.StreamIntegrityMonitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hybrid passive/active liveness state machine for one capture or authentication.
 *
 * <pre>
 * PASSIVE_CHECK ──score ≥ threshold, no PAD──────────────────────────► HOLD_STILL ──► PASSED
 *       │ score below threshold (or active.always)                        ▲
 *       ▼                                                                 │
 * ACTIVE_CHALLENGE ──action detected──► CHALLENGE_FEEDBACK ──last one─────┘
 *       ▲                                      │ more challenges
 *       └──────────────────────────────────────┘
 * Any phase ──PAD / injection / timeout / wrong action / extra face / face lost──► ATTEMPT_FAILED ──retry()──► PASSIVE_CHECK
 *                                                     ├──retry limit reached──► FAILED
 *                                                     └──rate limit reached──► FAILED (LOCKED_OUT)
 * </pre>
 *
 * PAD and face-continuity checks run on every frame in every phase, so a spoof cannot be swapped in
 * after the passive check. Every frame is also checked for signs of injection (a deepfake or recording fed in
 * through a virtual camera), and a station with a software camera the policy forbids is refused before the
 * camera is opened. Only a face inside the {@link CaptureArea} counts towards liveness or can
 * be captured; during verification, leaving the oval counts as losing the face. A covered face
 * (mask, sunglasses) is asked to uncover; if it stays covered for the grace period, verification falls
 * back to active challenges that do not need the covered region. The class is deterministic: time is passed in, so tests can drive it
 * frame by frame. Methods are synchronized; listener callbacks run on the calling thread.
 */
public final class LivenessSession {

    private static final Logger LOG = Logger.getLogger(LivenessSession.class.getName());

    /** How long "Action detected" is shown before the next challenge. */
    static final long FEEDBACK_MS = 600;
    /** Longest the face may be out of view once verification has started. */
    static final long MAX_FACE_LOSS_MS = 1500;
    /** Largest jump of the face centre between frames (fraction of frame) before continuity is broken. */
    static final float MAX_FACE_JUMP = 0.25f;
    /** Pose limit (degrees) for the captured frame. */
    static final float FRONTAL_MAX_ANGLE = 12f;
    private static final int MIN_ACTIVE_FRAMES_FOR_SCORE = 3;
    private static final float SCORE_SMOOTHING = 0.3f;
    /** Consecutive uncovered frames before an occlusion is considered gone (absorbs detector flicker). */
    static final int OCCLUSION_CLEAR_FRAMES = 3;

    private final String sessionId;
    private final LivenessWorkflow workflow;
    private final LivenessPolicy policy;
    private final EngineInfo engineInfo;
    private final ChallengeSelector selector;
    private final AuditSink audit;
    private final LivenessMetrics metrics;
    private final LivenessListener listener;
    private final AttemptRateLimiter rateLimiter;
    private final String rateLimitKey;

    private volatile LivenessState state = LivenessState.NOT_STARTED;
    private long sessionStart;
    private int attemptSequence;
    private int countedFailures;
    private final List<FailureReason> attemptFailures = new ArrayList<>();
    private List<ChallengeType> previousChallenges = Collections.emptyList();
    private FailureReason lastFailure;
    private FailureReason finalReason;
    private FailureAction requiredAction;
    private long lockedUntil;
    private CameraConsent consent;
    private CameraSourceReport sourceReport = CameraSourceReport.notApplicable();
    private final StreamIntegrityMonitor integrity = new StreamIntegrityMonitor();

    // Per-attempt state, reset by beginAttempt().
    private long phaseStart;
    private int framesThisAttempt;
    private PassiveEvaluator passive = new PassiveEvaluator();
    private int padAttackFrames;
    private int noFaceFrames;
    private int multiFaceFrames;
    private int poorQualityFrames;
    private int outOfAreaFrames;
    private boolean lastFaceOutOfArea;
    private int occludedFrames;
    private long occlusionSince = -1;
    private long lastOccludedAt = Long.MIN_VALUE;
    private int clearFramesInRow;
    private boolean occlusionFallback;
    private boolean occludedAtCapture;
    private final Set<FaceRegion> occludedRegions = EnumSet.noneOf(FaceRegion.class);
    private Set<FaceRegion> currentOcclusion = Collections.emptySet();
    private float passiveScore = Float.NaN;
    private FaceBaseline baseline;
    private List<ChallengeType> challenges = Collections.emptyList();
    private int challengeIndex = -1;
    private ActionDetector detector;
    private long challengeStart;
    private long feedbackUntil;
    private final List<ChallengeType> completed = new ArrayList<>();
    private boolean activePerformed;
    private float livenessEma = Float.NaN;
    private int activeFrames;
    private FaceObservation lastFace;
    private long lastFaceSeenAt;
    private FaceFrame bestFrame;
    private FaceObservation bestFace;
    private UserMessage guidance;

    public LivenessSession(String sessionId, LivenessWorkflow workflow, LivenessPolicy policy, EngineInfo engineInfo,
                           ChallengeSelector selector, AuditSink audit, LivenessMetrics metrics,
                           LivenessListener listener) {
        this(sessionId, workflow, policy, engineInfo, selector, audit, metrics, listener, null, null);
    }

    /**
     * @param rateLimiter  shared across sessions to lock out repeated failures; null disables rate limiting
     * @param rateLimitKey who the failures are counted against (see {@link AttemptRateLimiter})
     */
    public LivenessSession(String sessionId, LivenessWorkflow workflow, LivenessPolicy policy, EngineInfo engineInfo,
                           ChallengeSelector selector, AuditSink audit, LivenessMetrics metrics,
                           LivenessListener listener, AttemptRateLimiter rateLimiter, String rateLimitKey) {
        this.rateLimiter = rateLimiter;
        this.rateLimitKey = rateLimitKey;
        this.sessionId = sessionId;
        this.workflow = workflow;
        this.policy = policy;
        this.engineInfo = engineInfo;
        this.selector = selector;
        this.audit = audit;
        this.metrics = metrics;
        this.listener = listener;
    }

    public String getSessionId() {
        return sessionId;
    }

    public LivenessState state() {
        return state;
    }

    /** The person's answer to the camera notice; must be set before {@link #start} when the policy requires consent. */
    public synchronized void setCameraConsent(CameraConsent consent) {
        this.consent = consent;
    }

    /** Where the device's frames can come from, as found before the device is opened; set before {@link #start}. */
    public synchronized void setCameraSourceReport(CameraSourceReport report) {
        this.sourceReport = report == null ? CameraSourceReport.notApplicable() : report;
    }

    public synchronized void start(long now) {
        if (state != LivenessState.NOT_STARTED) {
            throw new IllegalStateException("Session already started");
        }
        sessionStart = now;
        metrics.sessionStarted();
        audit(AuditEventType.SESSION_STARTED, now,
                "engine", engineInfo.describe(), "policy", policy.summary(),
                "injection", (policy.isInjectionEnabled() ? "stream-checks" : "off") + "," + policy.getVirtualCameraPolicy());
        if (!checkConsent(now) || !checkCameraSource(now)) {
            return;
        }
        long until = rateLimited() ? rateLimiter.lockedUntil(rateLimitKey, now) : 0;
        if (until > 0) {
            lockOut(until, "locked before session start", now);
            return;
        }
        beginAttempt(now);
    }

    /** @return false if the session ended because camera use was not allowed */
    private boolean checkConsent(long now) {
        if (!policy.isConsentRequired()) {
            return true;
        }
        String problem = consent == null ? "not asked"
                : !consent.isGranted() ? "declined"
                : now - consent.getAnsweredAt() > policy.getConsentMaxAgeMs() ? "expired"
                : null;
        if (problem == null) {
            audit(AuditEventType.CONSENT_GRANTED, now, "noticeVersion", consent.getNoticeVersion(),
                    "language", consent.getLanguage(), "ageMs", String.valueOf(now - consent.getAnsweredAt()));
            return true;
        }
        audit(AuditEventType.CONSENT_NOT_GIVEN, now, "reason", problem,
                "noticeVersion", consent == null ? "n/a" : consent.getNoticeVersion());
        finalReason = FailureReason.CONSENT_DECLINED;
        requiredAction = policy.getRetryExhaustedAction();
        state = LivenessState.FAILED;
        metrics.cancelled();
        complete(LivenessOutcome.FAILED, now);
        return false;
    }

    /** @return false if the session ended because the station has a software camera the policy does not allow */
    private boolean checkCameraSource(long now) {
        if (sourceReport == CameraSourceReport.notApplicable()) {
            return true;
        }
        VirtualCameraPolicy rule = policy.getVirtualCameraPolicy();
        audit(AuditEventType.CAMERA_SOURCE_CHECKED, now, "result", sourceReport.describe(), "policy", rule.name());
        List<CameraEndpoint> blocking = !policy.isEnabled() || rule == VirtualCameraPolicy.AUDIT
                ? Collections.emptyList()
                : rule == VirtualCameraPolicy.BLOCK_INSTALLED
                ? sourceReport.virtualCameras() : sourceReport.reachableVirtualCameras();
        if (blocking.isEmpty()) {
            return true;
        }
        metrics.injectionDetected();
        audit(AuditEventType.INJECTION_DETECTED, now, "cues", "[" + InjectionCue.VIRTUAL_CAMERA + "]",
                "cameras", blocking.toString(), "phase", "START");
        finalReason = FailureReason.CAMERA_NOT_TRUSTED;
        requiredAction = FailureAction.BLOCK;
        state = LivenessState.FAILED;
        metrics.failed();
        audit(AuditEventType.LIVENESS_FAILED, now, "reason", finalReason.name(), "requiredAction", requiredAction.name());
        complete(LivenessOutcome.FAILED, now);
        return false;
    }

    private boolean rateLimited() {
        return rateLimiter != null && policy.isEnabled() && policy.isRateLimitEnabled();
    }

    /** Feeds the engine's analysis of one frame. Ignored unless the session is collecting frames. */
    public synchronized void onAnalysis(FrameAnalysis analysis, FaceFrame frame, long now) {
        if (!state.acceptsFrames()) {
            return;
        }
        framesThisAttempt++;
        boolean strict = policy.isEnabled() && state != LivenessState.PASSIVE_CHECK;
        List<FaceObservation> faces = analysis.getFaces();
        if (policy.isEnabled() && policy.isInjectionEnabled() && checkInjection(frame, faces.size() == 1, now)) {
            return;
        }

        if (faces.size() > 1) {
            multiFaceFrames++;
            if (strict) {
                failAttempt(FailureReason.MULTIPLE_FACES, faces.size() + " faces during verification", now);
                return;
            }
            guidance = UserMessage.GUIDE_ONE_FACE;
        } else if (faces.isEmpty()) {
            noFaceFrames++;
            guidance = UserMessage.GUIDE_FACE_IN_AREA;
        } else if (processFace(faces.get(0), analysis, frame, strict, now)) {
            return;
        }
        if (state.acceptsFrames()) {
            evaluateTimers(now);
        }
        if (state.acceptsFrames()) {
            emit(now);
        }
    }

    /** @return true if the attempt failed because the stream shows signs of injection */
    private boolean checkInjection(FaceFrame frame, boolean oneFace, long now) {
        Set<InjectionCue> cues = integrity.update(frame, oneFace);
        if (cues.isEmpty()) {
            return false;
        }
        metrics.injectionDetected();
        audit(AuditEventType.INJECTION_DETECTED, now, "cues", cues.toString(),
                "measurements", integrity.diagnostics(), "phase", state.name());
        failAttempt(FailureReason.INJECTION_DETECTED, "cues=" + cues, now);
        return true;
    }

    /** @return true if the attempt ended while processing this face */
    private boolean processFace(FaceObservation face, FrameAnalysis analysis, FaceFrame frame, boolean strict, long now) {
        if (policy.isEnabled() && policy.isPadEnabled() && analysis.getPad().isAttackDetected()) {
            padAttackFrames++;
            if (padAttackFrames >= policy.getPadMinAttackFrames()) {
                AttackType type = analysis.getPad().getAttackType();
                metrics.padDetected();
                audit(AuditEventType.PAD_ATTACK_DETECTED, now, "attackType", type.name(),
                        "indicators", analysis.getPad().getIndicators().toString(),
                        "attackFrames", String.valueOf(padAttackFrames),
                        "bonaFideScore", fmt(analysis.getPad().getBonaFideScore()), "phase", state.name());
                failAttempt(FailureReason.PAD_ATTACK_DETECTED, "attackType=" + type, now);
                return true;
            }
        }
        if (lastFace != null && lastFace.distanceTo(face) > MAX_FACE_JUMP) {
            if (strict) {
                failAttempt(FailureReason.CHALLENGE_FAILED, "face continuity lost", now);
                return true;
            }
            passive.reset();
        }
        lastFace = face;

        CaptureArea.Position position = policy.getCaptureArea().locate(face);
        if (position != CaptureArea.Position.INSIDE) {
            // Not counted. During verification the face-loss timer runs, so staying out fails the attempt.
            outOfAreaFrames++;
            lastFaceOutOfArea = true;
            guidance = guidanceFor(position);
            return false;
        }
        lastFaceOutOfArea = false;
        lastFaceSeenAt = now;

        boolean occluded = trackOcclusion(face, now);
        boolean goodQuality = face.getQuality() >= policy.getMinFaceQuality();
        if (goodQuality) {
            guidance = null;
        } else {
            poorQualityFrames++;
            guidance = UserMessage.GUIDE_IMPROVE_LIGHTING;
        }
        boolean blocksCapture = occluded && !policy.isOcclusionAllowCapture();
        if (occluded && (!occlusionFallback || (state == LivenessState.HOLD_STILL && blocksCapture))) {
            guidance = currentOcclusion.contains(FaceRegion.LOWER_FACE)
                    ? UserMessage.GUIDE_REMOVE_FACE_COVERING : UserMessage.GUIDE_REMOVE_SUNGLASSES;
        }

        switch (state) {
            case PASSIVE_CHECK:
                if (occluded && !occlusionFallback) {
                    if (now - occlusionSince < policy.getOcclusionGraceMs()) {
                        break;
                    }
                    enterOcclusionFallback(now);
                }
                if (goodQuality) {
                    passive.add(face, analysis.getLivenessScore());
                    if (passive.usableFrames() >= policy.getPassiveMinFrames()) {
                        decidePassive(now);
                    }
                }
                break;
            case ACTIVE_CHALLENGE:
                return onChallengeFrame(face, analysis, now);
            case CHALLENGE_FEEDBACK:
                return trackActiveLiveness(analysis, now);
            case HOLD_STILL:
                if (policy.isEnabled() && trackActiveLiveness(analysis, now)) {
                    return true;
                }
                if (blocksCapture) {
                    // Drop any frame picked before: a detector that misses the cover on one frame must not let it through.
                    occludedAtCapture = true;
                    bestFrame = null;
                    bestFace = null;
                } else if (goodQuality && isCapturable(face)
                        && (bestFace == null || face.getQuality() > bestFace.getQuality())) {
                    bestFace = face;
                    bestFrame = frame;
                }
                break;
            default:
                break;
        }
        return !state.acceptsFrames();
    }

    private static UserMessage guidanceFor(CaptureArea.Position position) {
        switch (position) {
            case TOO_FAR:
                return UserMessage.GUIDE_MOVE_CLOSER;
            case TOO_CLOSE:
                return UserMessage.GUIDE_MOVE_BACK;
            default:
                return UserMessage.GUIDE_FACE_IN_AREA;
        }
    }

    /**
     * @return true if the face counts as covered (occlusion handling enabled). Sticky: once seen, the face
     * stays covered until {@link #OCCLUSION_CLEAR_FRAMES} uncovered frames in a row, so a detector that
     * misses the mask on an occasional frame does not briefly "uncover" it.
     */
    private boolean trackOcclusion(FaceObservation face, long now) {
        if (!policy.isOcclusionEnabled() || !face.isOccluded()) {
            if (++clearFramesInRow >= OCCLUSION_CLEAR_FRAMES) {
                occlusionSince = -1;
                currentOcclusion = Collections.emptySet();
            }
            return occlusionSince >= 0;
        }
        clearFramesInRow = 0;
        occludedFrames++;
        lastOccludedAt = now;
        currentOcclusion = face.getOccludedRegions();
        occludedRegions.addAll(currentOcclusion);
        if (occlusionSince < 0) {
            occlusionSince = now;
        }
        return true;
    }

    /** The final image may be taken only after a full hold-still window with no cover seen. */
    private boolean clearOfOcclusion(long now) {
        return !policy.isOcclusionEnabled() || policy.isOcclusionAllowCapture()
                || lastOccludedAt == Long.MIN_VALUE || now - lastOccludedAt >= policy.getHoldStillMs();
    }

    /** The subject did not uncover in time: continue, but trust only challenges that avoid the covered region. */
    private void enterOcclusionFallback(long now) {
        occlusionFallback = true;
        audit(AuditEventType.OCCLUSION_FALLBACK, now, "regions", occludedRegions.toString(),
                "coveredForMs", String.valueOf(now - occlusionSince));
    }

    private void decidePassive(long now) {
        passiveScore = passive.score();
        baseline = passive.baseline();
        boolean meetsThreshold = passiveScore >= policy.getPassiveThreshold();
        // Passive PAD is less reliable on a covered face, so a fallback always needs active proof.
        boolean needsActive = !meetsThreshold || policy.isActiveAlways() || occlusionFallback;
        String decision = !needsActive ? "PASS" : policy.isActiveEnabled() ? "ACTIVE_REQUIRED" : "FAIL";
        audit(AuditEventType.PASSIVE_RESULT, now, "score", fmt(passiveScore),
                "threshold", String.valueOf(policy.getPassiveThreshold()),
                "frames", String.valueOf(passive.usableFrames()), "occlusionFallback", String.valueOf(occlusionFallback),
                "decision", decision);
        if ("PASS".equals(decision)) {
            enterHoldStill(now);
        } else if (policy.isActiveEnabled()) {
            startActive(now);
        } else if (occlusionFallback && meetsThreshold) {
            failAttempt(FailureReason.FACE_OCCLUDED, "covered face and active liveness disabled", now);
        } else {
            failAttempt(FailureReason.LIVENESS_BELOW_THRESHOLD, "passive score " + fmt(passiveScore), now);
        }
    }

    private void startActive(long now) {
        activePerformed = true;
        metrics.activeStarted();
        List<ChallengeType> allowed = new ArrayList<>();
        int available = 0;
        for (ChallengeType type : policy.getChallengeTypes()) {
            if (type.requiredRegion() == null || !occludedRegions.contains(type.requiredRegion())) {
                allowed.add(type);
                if (engineInfo.getSupportedChallenges().contains(type)) {
                    available++;
                }
            }
        }
        int min = policy.getMinChallenges();
        if (!occludedRegions.isEmpty()) {
            if (available == 0) {
                failAttempt(FailureReason.FACE_OCCLUDED,
                        "no supported challenge avoids covered " + occludedRegions, now);
                return;
            }
            min = Math.min(min, available);
        }
        try {
            challenges = selector.select(allowed, min, Math.max(min, policy.getMaxChallenges()),
                    engineInfo.getSupportedChallenges(), previousChallenges);
        } catch (IllegalStateException e) {
            failAttempt(FailureReason.CONFIGURATION_ERROR, e.getMessage(), now);
            return;
        }
        previousChallenges = challenges;
        challengeIndex = -1;
        nextChallenge(now);
    }

    private void nextChallenge(long now) {
        challengeIndex++;
        if (challengeIndex >= challenges.size()) {
            enterHoldStill(now);
            return;
        }
        ChallengeType type = challenges.get(challengeIndex);
        detector = ActionDetectors.create(type, baseline, policy.getHeadYawDegrees(), policy.getHeadPitchDegrees());
        challengeStart = now;
        state = LivenessState.ACTIVE_CHALLENGE;
        audit(AuditEventType.CHALLENGE_ISSUED, now, "challenge", type.name(),
                "index", (challengeIndex + 1) + "/" + challenges.size());
    }

    /** @return true if the attempt ended */
    private boolean onChallengeFrame(FaceObservation face, FrameAnalysis analysis, long now) {
        if (trackActiveLiveness(analysis, now)) {
            return true;
        }
        ChallengeType type = detector.type();
        if (detector.contradicts(face)) {
            audit(AuditEventType.CHALLENGE_FAILED, now, "challenge", type.name(), "cause", "opposite action");
            failAttempt(FailureReason.CHALLENGE_FAILED, "opposite of " + type + " performed", now);
            return true;
        }
        ActionDetector.Progress progress = detector.update(face);
        if (progress == ActionDetector.Progress.COMPLETED) {
            long took = now - challengeStart;
            completed.add(type);
            metrics.challengeCompleted(took);
            audit(AuditEventType.CHALLENGE_PASSED, now, "challenge", type.name(), "durationMs", String.valueOf(took));
            state = LivenessState.CHALLENGE_FEEDBACK;
            feedbackUntil = now + FEEDBACK_MS;
        } else if (progress == ActionDetector.Progress.IN_PROGRESS && guidance == null) {
            guidance = UserMessage.FEEDBACK_CONTINUE;
        }
        return false;
    }

    /**
     * Re-evaluates liveness while challenges are performed: the smoothed score must stay above the
     * active floor. @return true if the attempt failed
     */
    private boolean trackActiveLiveness(FrameAnalysis analysis, long now) {
        float score = analysis.getLivenessScore();
        if (Float.isNaN(score)) {
            return false;
        }
        activeFrames++;
        livenessEma = Float.isNaN(livenessEma) ? score : SCORE_SMOOTHING * score + (1 - SCORE_SMOOTHING) * livenessEma;
        if (activeFrames >= MIN_ACTIVE_FRAMES_FOR_SCORE && livenessEma < policy.getActiveMinLivenessScore()) {
            failAttempt(FailureReason.LIVENESS_BELOW_THRESHOLD, "liveness during verification " + fmt(livenessEma), now);
            return true;
        }
        return false;
    }

    private void enterHoldStill(long now) {
        state = LivenessState.HOLD_STILL;
        phaseStart = now;
        bestFrame = null;
        bestFace = null;
    }

    private boolean isCapturable(FaceObservation face) {
        // Pitch is judged against the person's resting pose: a camera above or below eye level shifts the
        // absolute angle, while what matters here is that they are no longer looking up or down for a challenge.
        float pitchReference = baseline == null || Float.isNaN(baseline.getPitch()) ? 0f : baseline.getPitch();
        if (Math.abs(face.getYaw()) > FRONTAL_MAX_ANGLE || Math.abs(face.getPitch() - pitchReference) > FRONTAL_MAX_ANGLE) {
            return false;
        }
        float eyes = face.eyeOpenness();
        if (Float.isNaN(eyes)) {
            return true;
        }
        float reference = baseline == null ? 0.8f : Math.max(baseline.getEyeOpenness(), 0.5f);
        return eyes >= reference * 0.6f;
    }

    /** Called periodically so that timeouts fire even when the device stops sending frames. */
    public synchronized void onTick(long now) {
        if (!state.acceptsFrames()) {
            return;
        }
        LivenessState before = state;
        int index = challengeIndex;
        evaluateTimers(now);
        if (state.acceptsFrames() && (state != before || challengeIndex != index)) {
            emit(now);
        }
    }

    private void evaluateTimers(long now) {
        boolean verifying = policy.isEnabled() && state != LivenessState.PASSIVE_CHECK;
        if (verifying && lastFace != null && now - lastFaceSeenAt > MAX_FACE_LOSS_MS) {
            if (lastFaceOutOfArea) {
                failAttempt(FailureReason.FACE_NOT_POSITIONED, "face left the capture area during " + state, now);
            } else {
                failAttempt(FailureReason.FACE_NOT_DETECTED, "face lost during " + state, now);
            }
            return;
        }
        switch (state) {
            case PASSIVE_CHECK:
                if (now - phaseStart > policy.getPassiveTimeoutMs()) {
                    failAttempt(dominantFrameProblem(), "passive check timed out", now);
                }
                break;
            case ACTIVE_CHALLENGE:
                if (now - challengeStart > policy.getChallengeTimeoutMs()) {
                    audit(AuditEventType.CHALLENGE_FAILED, now, "challenge", detector.type().name(), "cause", "timeout");
                    failAttempt(FailureReason.CHALLENGE_TIMEOUT, detector.type() + " not performed in time", now);
                }
                break;
            case CHALLENGE_FEEDBACK:
                if (now >= feedbackUntil) {
                    nextChallenge(now);
                }
                break;
            case HOLD_STILL:
                long held = now - phaseStart;
                if (bestFrame != null && held >= policy.getHoldStillMs() && clearOfOcclusion(now)) {
                    pass(now);
                } else if (held > policy.getHoldStillMs() + policy.getPassiveTimeoutMs()) {
                    if (occludedAtCapture) {
                        failAttempt(FailureReason.FACE_OCCLUDED, "face stayed covered at capture", now);
                    } else {
                        failAttempt(policy.isEnabled() ? FailureReason.POOR_FACE_QUALITY : dominantFrameProblem(),
                                "no frontal, eyes-open frame of sufficient quality", now);
                    }
                }
                break;
            default:
                break;
        }
    }

    private FailureReason dominantFrameProblem() {
        if (framesThisAttempt == 0) {
            return FailureReason.DEVICE_DISCONNECTED;
        }
        // Most frequent problem wins; ties go to the earlier entry.
        int[] counts = {noFaceFrames, multiFaceFrames, outOfAreaFrames, occludedFrames, poorQualityFrames};
        FailureReason[] reasons = {FailureReason.FACE_NOT_DETECTED, FailureReason.MULTIPLE_FACES,
                FailureReason.FACE_NOT_POSITIONED, FailureReason.FACE_OCCLUDED, FailureReason.POOR_FACE_QUALITY};
        int best = reasons.length - 1;
        for (int i = reasons.length - 1; i >= 0; i--) {
            if (counts[i] > 0 && counts[i] >= counts[best]) {
                best = i;
            }
        }
        return reasons[best];
    }

    /** Device, frame or engine fault reported by the service. Does not consume the retry budget. */
    public synchronized void onSystemError(FailureReason reason, String detail, long now) {
        if (!state.acceptsFrames()) {
            return;
        }
        audit(AuditEventType.SYSTEM_ERROR, now, "reason", reason.name(), "detail", detail);
        failAttempt(reason, detail, now);
    }

    /** Starts a new attempt after a failed one. @return false if no retry is available */
    public synchronized boolean retry(long now) {
        if (state != LivenessState.ATTEMPT_FAILED) {
            return false;
        }
        metrics.retried();
        beginAttempt(now);
        return true;
    }

    public synchronized void cancel(long now) {
        if (state.isTerminal()) {
            return;
        }
        state = LivenessState.CANCELLED;
        metrics.cancelled();
        audit(AuditEventType.SESSION_CANCELLED, now);
        complete(LivenessOutcome.CANCELLED, now);
    }

    private void beginAttempt(long now) {
        attemptSequence++;
        phaseStart = now;
        framesThisAttempt = 0;
        passive = new PassiveEvaluator();
        padAttackFrames = 0;
        integrity.reset();
        noFaceFrames = 0;
        multiFaceFrames = 0;
        poorQualityFrames = 0;
        outOfAreaFrames = 0;
        lastFaceOutOfArea = false;
        occludedFrames = 0;
        occlusionSince = -1;
        lastOccludedAt = Long.MIN_VALUE;
        clearFramesInRow = 0;
        occlusionFallback = false;
        occludedAtCapture = false;
        occludedRegions.clear();
        currentOcclusion = Collections.emptySet();
        passiveScore = Float.NaN;
        baseline = null;
        challenges = Collections.emptyList();
        challengeIndex = -1;
        detector = null;
        completed.clear();
        activePerformed = false;
        livenessEma = Float.NaN;
        activeFrames = 0;
        lastFace = null;
        lastFaceSeenAt = 0;
        bestFrame = null;
        bestFace = null;
        guidance = null;
        lastFailure = null;
        metrics.attemptStarted();
        audit(AuditEventType.ATTEMPT_STARTED, now);
        if (policy.isEnabled()) {
            state = LivenessState.PASSIVE_CHECK;
        } else {
            enterHoldStill(now);
        }
        emit(now);
    }

    private void failAttempt(FailureReason reason, String detail, long now) {
        attemptFailures.add(reason);
        lastFailure = reason;
        if (reason.consumesAttempt()) {
            countedFailures++;
        }
        audit(AuditEventType.ATTEMPT_FAILED, now, "reason", reason.name(), "detail", detail,
                "phase", state.name(), "countedFailures", String.valueOf(countedFailures));
        if (reason.consumesAttempt() && rateLimited()) {
            long until = rateLimiter.recordFailure(rateLimitKey, policy.getRateLimitMaxFailures(),
                    policy.getRateLimitWindowMs(), policy.getRateLimitLockoutMs(), now);
            if (until > 0) {
                lockOut(until, "too many failed attempts", now);
                return;
            }
        }
        boolean exhausted = countedFailures > policy.getMaxRetries();
        if (reason.isTerminal() || exhausted) {
            finalReason = exhausted ? FailureReason.MAX_RETRIES_EXCEEDED : reason;
            requiredAction = exhausted ? policy.getRetryExhaustedAction() : FailureAction.BLOCK;
            state = LivenessState.FAILED;
            metrics.failed();
            audit(AuditEventType.LIVENESS_FAILED, now, "reason", finalReason.name(),
                    "lastAttemptReason", reason.name(), "requiredAction", requiredAction.name(),
                    "attemptFailures", attemptFailures.toString());
            complete(LivenessOutcome.FAILED, now);
            return;
        }
        state = LivenessState.ATTEMPT_FAILED;
        emit(now);
    }

    private void lockOut(long until, String detail, long now) {
        lockedUntil = until;
        finalReason = FailureReason.LOCKED_OUT;
        requiredAction = FailureAction.BLOCK;
        state = LivenessState.FAILED;
        metrics.failed();
        audit(AuditEventType.RATE_LIMIT_LOCKOUT, now, "lockedUntil", Instant.ofEpochMilli(until).toString(),
                "detail", detail, "maxFailures", String.valueOf(policy.getRateLimitMaxFailures()),
                "windowMs", String.valueOf(policy.getRateLimitWindowMs()));
        audit(AuditEventType.LIVENESS_FAILED, now, "reason", finalReason.name(),
                "requiredAction", requiredAction.name(), "attemptFailures", attemptFailures.toString());
        complete(LivenessOutcome.FAILED, now);
    }

    private void pass(long now) {
        state = LivenessState.PASSED;
        metrics.passed(!activePerformed);
        audit(AuditEventType.LIVENESS_PASSED, now, "livenessEnabled", String.valueOf(policy.isEnabled()),
                "passiveScore", fmt(passiveScore), "active", String.valueOf(activePerformed),
                "challenges", completed.toString(), "captureQuality", String.valueOf(bestFace.getQuality()));
        complete(policy.isEnabled() ? LivenessOutcome.PASSED : LivenessOutcome.LIVENESS_DISABLED, now);
    }

    private void complete(LivenessOutcome outcome, long now) {
        boolean accepted = outcome == LivenessOutcome.PASSED || outcome == LivenessOutcome.LIVENESS_DISABLED;
        LivenessResult result = new LivenessResult(sessionId, workflow, outcome,
                outcome == LivenessOutcome.FAILED ? finalReason : null,
                outcome == LivenessOutcome.FAILED ? requiredAction : null,
                accepted ? bestFrame : null, accepted ? bestFace : null, passiveScore, activePerformed,
                completed, attemptSequence, attemptFailures, engineInfo.describe(), now - sessionStart, lockedUntil);
        emit(now);
        try {
            listener.onComplete(result);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Liveness listener failed in onComplete", e);
        }
    }

    private void emit(long now) {
        int maxAttempts = policy.getMaxRetries() + 1;
        boolean failedAttemptCounted = (state == LivenessState.ATTEMPT_FAILED || state == LivenessState.FAILED)
                && lastFailure != null && lastFailure.consumesAttempt();
        int attempt = Math.min(countedFailures + (failedAttemptCounted ? 0 : 1), maxAttempts);
        boolean inChallenge = state == LivenessState.ACTIVE_CHALLENGE || state == LivenessState.CHALLENGE_FEEDBACK;
        LivenessUpdate update = new LivenessUpdate(sessionId, workflow, state, message(),
                state.acceptsFrames() ? guidance : null,
                state == LivenessState.ATTEMPT_FAILED && lastFailure != null ? lastFailure.tips() : Collections.emptyList(),
                inChallenge ? challenges.get(challengeIndex) : null,
                challengeIndex >= 0 ? Math.min(challengeIndex + 1, challenges.size()) : 0,
                challenges.size(), progress(now),
                state == LivenessState.ACTIVE_CHALLENGE
                        ? Math.max(0, policy.getChallengeTimeoutMs() - (now - challengeStart)) : -1,
                attemptSequence, attempt, maxAttempts, state == LivenessState.ATTEMPT_FAILED);
        try {
            listener.onUpdate(update);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Liveness listener failed in onUpdate", e);
        }
    }

    private UserMessage message() {
        switch (state) {
            case PASSIVE_CHECK:
                return UserMessage.CHECKING_LIVENESS;
            case ACTIVE_CHALLENGE:
                return UserMessage.forChallenge(challenges.get(challengeIndex));
            case CHALLENGE_FEEDBACK:
                return UserMessage.FEEDBACK_ACTION_DETECTED;
            case HOLD_STILL:
                return UserMessage.FEEDBACK_HOLD_STILL;
            case PASSED:
                return UserMessage.SUCCESS;
            case ATTEMPT_FAILED:
                return lastFailure.message();
            case FAILED:
                if (finalReason == FailureReason.LOCKED_OUT) {
                    return UserMessage.FINAL_LOCKED_OUT;
                }
                if (finalReason == FailureReason.CONSENT_DECLINED) {
                    return UserMessage.FAIL_CONSENT;
                }
                if (finalReason == FailureReason.CAMERA_NOT_TRUSTED) {
                    return UserMessage.FAIL_CAMERA_NOT_TRUSTED;
                }
                if (finalReason != FailureReason.MAX_RETRIES_EXCEEDED) {
                    return UserMessage.FINAL_BLOCKED;
                }
                switch (requiredAction) {
                    case REQUIRE_SUPERVISOR_OVERRIDE:
                        return UserMessage.FINAL_SUPERVISOR_OVERRIDE;
                    case ALLOW_ALTERNATE_AUTH:
                        return UserMessage.FINAL_ALTERNATE_AUTH;
                    default:
                        return UserMessage.FINAL_BLOCKED;
                }
            case CANCELLED:
                return UserMessage.CANCELLED;
            default:
                return UserMessage.CHECKING_LIVENESS;
        }
    }

    /** Passive 0–40%, challenges 40–90%, capture 90–100%. */
    private double progress(long now) {
        switch (state) {
            case PASSIVE_CHECK:
                return 0.4 * Math.min(1.0, passive.usableFrames() / (double) policy.getPassiveMinFrames());
            case ACTIVE_CHALLENGE:
                return 0.4 + 0.5 * challengeIndex / challenges.size();
            case CHALLENGE_FEEDBACK:
                return 0.4 + 0.5 * (challengeIndex + 1) / challenges.size();
            case HOLD_STILL:
                double held = policy.getHoldStillMs() == 0 ? 1 : (now - phaseStart) / (double) policy.getHoldStillMs();
                return 0.9 + 0.1 * Math.min(1.0, held);
            case PASSED:
                return 1.0;
            default:
                return 0.0;
        }
    }

    private void audit(AuditEventType type, long now, String... keyValues) {
        Map<String, String> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            details.put(keyValues[i], keyValues[i + 1]);
        }
        try {
            audit.record(new AuditEvent(type, sessionId, workflow, attemptSequence, Instant.ofEpochMilli(now), details));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Audit sink failed for " + type, e);
        }
    }

    private static String fmt(float value) {
        return Float.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.1f", value);
    }
}
