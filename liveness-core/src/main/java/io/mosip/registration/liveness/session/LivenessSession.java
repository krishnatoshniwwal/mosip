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
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.AttackType;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.FrameAnalysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Any phase ──PAD / timeout / wrong action / extra face / face lost──► ATTEMPT_FAILED ──retry()──► PASSIVE_CHECK
 *                                                     └──retry limit reached──► FAILED
 * </pre>
 *
 * PAD and face-continuity checks run on every frame in every phase, so a spoof cannot be swapped in
 * after the passive check. The class is deterministic: time is passed in, so tests can drive it
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

    private final String sessionId;
    private final LivenessWorkflow workflow;
    private final LivenessPolicy policy;
    private final EngineInfo engineInfo;
    private final ChallengeSelector selector;
    private final AuditSink audit;
    private final LivenessMetrics metrics;
    private final LivenessListener listener;

    private volatile LivenessState state = LivenessState.NOT_STARTED;
    private long sessionStart;
    private int attemptSequence;
    private int countedFailures;
    private final List<FailureReason> attemptFailures = new ArrayList<>();
    private List<ChallengeType> previousChallenges = Collections.emptyList();
    private FailureReason lastFailure;
    private FailureReason finalReason;
    private FailureAction requiredAction;

    // Per-attempt state, reset by beginAttempt().
    private long phaseStart;
    private int framesThisAttempt;
    private PassiveEvaluator passive = new PassiveEvaluator();
    private int padAttackFrames;
    private int noFaceFrames;
    private int multiFaceFrames;
    private int poorQualityFrames;
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

    public synchronized void start(long now) {
        if (state != LivenessState.NOT_STARTED) {
            throw new IllegalStateException("Session already started");
        }
        sessionStart = now;
        metrics.sessionStarted();
        audit(AuditEventType.SESSION_STARTED, now,
                "engine", engineInfo.describe(), "policy", policy.summary());
        beginAttempt(now);
    }

    /** Feeds the engine's analysis of one frame. Ignored unless the session is collecting frames. */
    public synchronized void onAnalysis(FrameAnalysis analysis, FaceFrame frame, long now) {
        if (!state.acceptsFrames()) {
            return;
        }
        framesThisAttempt++;
        boolean strict = policy.isEnabled() && state != LivenessState.PASSIVE_CHECK;
        List<FaceObservation> faces = analysis.getFaces();

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

    /** @return true if the attempt ended while processing this face */
    private boolean processFace(FaceObservation face, FrameAnalysis analysis, FaceFrame frame, boolean strict, long now) {
        if (policy.isEnabled() && policy.isPadEnabled() && analysis.getPad().isAttackDetected()) {
            padAttackFrames++;
            if (padAttackFrames >= policy.getPadMinAttackFrames()) {
                AttackType type = analysis.getPad().getAttackType();
                metrics.padDetected();
                audit(AuditEventType.PAD_ATTACK_DETECTED, now, "attackType", type.name(),
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
        lastFaceSeenAt = now;

        boolean goodQuality = face.getQuality() >= policy.getMinFaceQuality();
        if (goodQuality) {
            guidance = null;
        } else {
            poorQualityFrames++;
            guidance = UserMessage.GUIDE_IMPROVE_LIGHTING;
        }

        switch (state) {
            case PASSIVE_CHECK:
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
                if (goodQuality && isCapturable(face)
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

    private void decidePassive(long now) {
        passiveScore = passive.score();
        baseline = passive.baseline();
        boolean meetsThreshold = passiveScore >= policy.getPassiveThreshold();
        String decision = meetsThreshold && !policy.isActiveAlways() ? "PASS"
                : policy.isActiveEnabled() ? "ACTIVE_REQUIRED" : "FAIL";
        audit(AuditEventType.PASSIVE_RESULT, now, "score", fmt(passiveScore),
                "threshold", String.valueOf(policy.getPassiveThreshold()),
                "frames", String.valueOf(passive.usableFrames()), "decision", decision);
        if ("PASS".equals(decision)) {
            enterHoldStill(now);
        } else if (policy.isActiveEnabled()) {
            startActive(now);
        } else {
            failAttempt(FailureReason.LIVENESS_BELOW_THRESHOLD, "passive score " + fmt(passiveScore), now);
        }
    }

    private void startActive(long now) {
        activePerformed = true;
        metrics.activeStarted();
        try {
            challenges = selector.select(policy.getChallengeTypes(), policy.getMinChallenges(),
                    policy.getMaxChallenges(), engineInfo.getSupportedChallenges(), previousChallenges);
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
        if (Math.abs(face.getYaw()) > FRONTAL_MAX_ANGLE || Math.abs(face.getPitch()) > FRONTAL_MAX_ANGLE) {
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
            failAttempt(FailureReason.FACE_NOT_DETECTED, "face lost during " + state, now);
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
                if (bestFrame != null && held >= policy.getHoldStillMs()) {
                    pass(now);
                } else if (held > policy.getHoldStillMs() + policy.getPassiveTimeoutMs()) {
                    failAttempt(policy.isEnabled() ? FailureReason.POOR_FACE_QUALITY : dominantFrameProblem(),
                            "no frontal, eyes-open frame of sufficient quality", now);
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
        if (noFaceFrames >= multiFaceFrames && noFaceFrames >= poorQualityFrames && noFaceFrames > 0) {
            return FailureReason.FACE_NOT_DETECTED;
        }
        if (multiFaceFrames >= poorQualityFrames && multiFaceFrames > 0) {
            return FailureReason.MULTIPLE_FACES;
        }
        return FailureReason.POOR_FACE_QUALITY;
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
        noFaceFrames = 0;
        multiFaceFrames = 0;
        poorQualityFrames = 0;
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
                completed, attemptSequence, attemptFailures, engineInfo.describe(), now - sessionStart);
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
