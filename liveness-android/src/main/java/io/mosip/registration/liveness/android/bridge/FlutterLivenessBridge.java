package io.mosip.registration.liveness.android.bridge;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.CaptureArea;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.i18n.LivenessMessages;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.service.LivenessHandle;
import io.mosip.registration.liveness.session.CameraConsent;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessState;
import io.mosip.registration.liveness.session.LivenessUpdate;
import io.mosip.registration.liveness.session.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Native bridge connecting MOSIP Android Registration Client's Flutter layer
 * with {@link FaceLivenessService}.
 *
 * <p>Implements the standard Flutter MethodChannel / EventChannel contract:
 * <ul>
 *   <li><b>MethodChannel (incoming commands):</b> {@code start}, {@code retry}, {@code cancel}, {@code getStatus}</li>
 *   <li><b>EventChannel (outgoing stream):</b> streams {@link LivenessUpdate} and final {@link LivenessResult}</li>
 * </ul>
 *
 * <p>Every update carries the texts to show, already translated from the same bundles the desktop dialog uses
 * ({@code messageText}, {@code guidanceText}, {@code tipsText}, {@code stepText}, {@code timeLeftText},
 * {@code attemptText}), so both clients say exactly the same thing. The English {@code message}/{@code guidance}
 * and the {@code *Code} fields stay for callers that translate themselves.
 */
public class FlutterLivenessBridge {

    /** Callback interface mimicking Flutter's {@code MethodChannel.Result}. */
    public interface MethodResult {
        void success(Object result);
        void error(String errorCode, String errorMessage, Object errorDetails);
        void notImplemented();
    }

    /** Turns the verified frame into the image handed back to Flutter (JPEG), e.g. with Android's YuvImage. */
    public interface FrameEncoder {
        byte[] toJpeg(FaceFrame frame) throws Exception;
    }

    /** Stream sink interface mimicking Flutter's {@code EventChannel.EventSink}. */
    public interface EventSink {
        void success(Object event);
        void error(String errorCode, String errorMessage, Object errorDetails);
        void endOfStream();
    }

    private final FaceLivenessService service;
    private final FaceCaptureDevice device;
    private final LivenessConfig config;
    private final AtomicReference<LivenessHandle> activeHandle = new AtomicReference<>();
    private volatile EventSink activeEventSink;
    private volatile FrameEncoder frameEncoder;

    private static final Logger LOG = Logger.getLogger(FlutterLivenessBridge.class.getName());

    public FlutterLivenessBridge(FaceLivenessService service, FaceCaptureDevice device, LivenessConfig config) {
        this.service = Objects.requireNonNull(service, "service");
        this.device = Objects.requireNonNull(device, "device");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void setEventSink(EventSink sink) {
        this.activeEventSink = sink;
    }

    /** Without an encoder the result carries the frame's size and format but not the image. */
    public void setFrameEncoder(FrameEncoder encoder) {
        this.frameEncoder = encoder;
    }

    /**
     * Dispatcher for Flutter {@code MethodChannel.onMethodCall}.
     *
     * @param method method name called from Flutter Dart code
     * @param args   arguments map passed from Flutter
     * @param result response callback back to Flutter
     */
    public void onMethodCall(String method, Map<String, Object> args, MethodResult result) {
        if (args == null) {
            args = Collections.emptyMap();
        }
        try {
            switch (method) {
                case "start":
                    handleStart(args, result);
                    break;
                case "retry":
                    handleRetry(result);
                    break;
                case "cancel":
                    handleCancel(result);
                    break;
                case "getStatus":
                    handleGetStatus(result);
                    break;
                default:
                    result.notImplemented();
                    break;
            }
        } catch (Exception e) {
            result.error("LIVENESS_BRIDGE_ERROR", e.getMessage(), null);
        }
    }

    private void handleStart(Map<String, Object> args, MethodResult result) {
        String workflowStr = (String) args.getOrDefault("workflow", "RESIDENT_REGISTRATION");
        LivenessWorkflow workflow;
        try {
            workflow = LivenessWorkflow.valueOf(workflowStr);
        } catch (IllegalArgumentException e) {
            result.error("INVALID_WORKFLOW", "Unknown workflow: " + workflowStr, null);
            return;
        }

        // Camera consent parsing
        boolean consentGranted = Boolean.TRUE.equals(args.get("consentGranted"));
        String consentVersion = (String) args.getOrDefault("consentNoticeVersion", LivenessMessages.CONSENT_NOTICE_VERSION);
        LivenessMessages messages = LivenessMessages.forLanguage((String) args.getOrDefault("language", "en"));
        String language = messages.language();
        // Who failed attempts count against: the operator or supervisor user ID, or the registration ID.
        String subjectKey = (String) args.get("subjectKey");
        long now = System.currentTimeMillis();
        CameraConsent consent = consentGranted
                ? CameraConsent.granted(consentVersion, language, now)
                : CameraConsent.declined(consentVersion, language, now);

        LivenessListener listener = new LivenessListener() {
            @Override
            public void onUpdate(LivenessUpdate update) {
                EventSink sink = activeEventSink;
                if (sink != null) {
                    sink.success(serializeUpdate(update, messages));
                }
            }

            @Override
            public void onComplete(LivenessResult r) {
                EventSink sink = activeEventSink;
                if (sink != null) {
                    sink.success(serializeResult(r, messages, frameEncoder));
                }
                activeHandle.set(null);
            }
        };

        // One session at a time per device: a new start replaces a session the screen abandoned.
        LivenessHandle previous = activeHandle.getAndSet(null);
        if (previous != null) {
            previous.cancel();
        }
        LivenessHandle handle = service.start(workflow, subjectKey, consent, device, listener);
        activeHandle.set(handle);

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", handle.getSessionId());
        response.put("status", handle.state().name());
        LivenessPolicy policy = config.policyFor(workflow);
        response.put("captureArea", serializeCaptureArea(policy.getCaptureArea()));
        response.put("language", language);
        response.put("rtl", messages.isRightToLeft());
        response.put("ui", uiLabels(messages));

        result.success(response);
    }

    private void handleRetry(MethodResult result) {
        LivenessHandle handle = activeHandle.get();
        if (handle == null) {
            result.error("NO_ACTIVE_SESSION", "No liveness session is currently running", null);
            return;
        }
        handle.retry();
        result.success(Collections.singletonMap("status", handle.state().name()));
    }

    private void handleCancel(MethodResult result) {
        LivenessHandle handle = activeHandle.getAndSet(null);
        if (handle != null) {
            handle.cancel();
            result.success(Collections.singletonMap("status", "CANCELLED"));
        } else {
            result.success(Collections.singletonMap("status", "IDLE"));
        }
    }

    private void handleGetStatus(MethodResult result) {
        LivenessHandle handle = activeHandle.get();
        Map<String, Object> statusMap = new HashMap<>();
        statusMap.put("active", handle != null);
        statusMap.put("deviceStatus", device.status().name());
        if (handle != null) {
            statusMap.put("sessionId", handle.getSessionId());
            statusMap.put("state", handle.state().name());
        }
        result.success(statusMap);
    }

    // ---- Serialization to Flutter Map/JSON ----

    public static Map<String, Object> serializeUpdate(LivenessUpdate u) {
        Map<String, Object> map = new HashMap<>();
        map.put("eventType", "update");
        map.put("sessionId", u.getSessionId());
        map.put("workflow", u.getWorkflow().name());
        map.put("state", u.getState().name());
        map.put("message", u.getMessage().defaultText());
        map.put("messageCode", u.getMessage().name());
        if (u.getGuidance() != null) {
            map.put("guidance", u.getGuidance().defaultText());
            map.put("guidanceCode", u.getGuidance().name());
        }
        if (u.getCurrentChallenge() != null) {
            map.put("challenge", u.getCurrentChallenge().name());
        }
        map.put("challengeNumber", u.getChallengeNumber());
        map.put("challengeCount", u.getChallengeCount());
        map.put("remainingMs", u.getChallengeRemainingMs());
        map.put("progress", u.getProgress());
        map.put("attempt", u.getAttempt());
        map.put("maxAttempts", u.getMaxAttempts());
        map.put("retryAvailable", u.isRetryAvailable());

        List<String> tips = new ArrayList<>();
        for (UserMessage tip : u.getTips()) {
            tips.add(tip.defaultText());
        }
        map.put("tips", tips);

        return map;
    }

    /** {@link #serializeUpdate(LivenessUpdate)} plus the screen texts in the session's language. */
    public static Map<String, Object> serializeUpdate(LivenessUpdate u, LivenessMessages messages) {
        Map<String, Object> map = serializeUpdate(u);
        LivenessState state = u.getState();
        map.put("messageText", messages.text(u.getMessage()));
        if (u.getGuidance() != null) {
            map.put("guidanceText", messages.text(u.getGuidance()));
        }
        List<String> tips = new ArrayList<>();
        for (UserMessage tip : u.getTips()) {
            tips.add(messages.text(tip));
        }
        map.put("tipsText", tips);
        boolean inChallenge = state == LivenessState.ACTIVE_CHALLENGE || state == LivenessState.CHALLENGE_FEEDBACK;
        if (inChallenge) {
            map.put("stepText", messages.ui(LivenessMessages.UI_CHALLENGE, u.getChallengeNumber(), u.getChallengeCount()));
        }
        if (u.getChallengeRemainingMs() >= 0) {
            map.put("timeLeftText", messages.ui(LivenessMessages.UI_TIME_LEFT, (u.getChallengeRemainingMs() + 999) / 1000));
        }
        if (!state.isTerminal()) {
            map.put("attemptText", messages.ui(LivenessMessages.UI_ATTEMPT, u.getAttempt(), u.getMaxAttempts()));
        }
        return map;
    }

    /**
     * {@link #serializeResult(LivenessResult)} plus the lockout end, and for an accepted result the verified face as
     * {@code capturedJpeg}: on Android the camera frame that passed is the captured face, so the caller stores or
     * matches exactly what liveness verified.
     */
    public static Map<String, Object> serializeResult(LivenessResult r, LivenessMessages messages, FrameEncoder encoder) {
        Map<String, Object> map = serializeResult(r);
        if (r.getLockedUntil() > 0) {
            map.put("lockedUntil", r.getLockedUntil());
        }
        FaceFrame frame = r.getCapturedFrame();
        if (r.isAccepted() && frame != null && encoder != null) {
            try {
                map.put("capturedJpeg", encoder.toJpeg(frame));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not encode the captured face", e);
            }
        }
        return map;
    }

    /** Labels for the capture screen's buttons, notices and badges, in the session's language. */
    public static Map<String, Object> uiLabels(LivenessMessages messages) {
        Map<String, Object> labels = new HashMap<>();
        for (String key : new String[]{LivenessMessages.UI_RETRY, LivenessMessages.UI_CANCEL, LivenessMessages.UI_CLOSE,
                LivenessMessages.UI_PRESS_RETRY, LivenessMessages.UI_CONSENT_TITLE, LivenessMessages.UI_CONSENT_TEXT,
                LivenessMessages.UI_ALLOW, LivenessMessages.UI_DENY, LivenessMessages.UI_ON_DEVICE,
                LivenessMessages.UI_ONLINE, LivenessMessages.UI_OFFLINE, LivenessMessages.UI_LOCKED_UNTIL}) {
            labels.put(key.substring("liveness.ui.".length()), messages.ui(key));
        }
        labels.put("consentFailed", messages.text(UserMessage.FAIL_CONSENT));
        return labels;
    }

    public static Map<String, Object> serializeResult(LivenessResult r) {
        Map<String, Object> map = new HashMap<>();
        map.put("eventType", "complete");
        map.put("sessionId", r.getSessionId());
        map.put("workflow", r.getWorkflow().name());
        map.put("outcome", r.getOutcome().name());
        map.put("accepted", r.isAccepted());
        map.put("passiveScore", Float.isNaN(r.getPassiveScore()) ? null : (double) r.getPassiveScore());
        map.put("activeVerificationPerformed", r.isActiveVerificationPerformed());

        List<String> challenges = new ArrayList<>();
        if (r.getChallengesCompleted() != null) {
            for (ChallengeType c : r.getChallengesCompleted()) {
                challenges.add(c.name());
            }
        }
        map.put("challengesCompleted", challenges);

        if (r.getFailureReason() != null) {
            map.put("failureReason", r.getFailureReason().name());
        }
        if (r.getRequiredAction() != null) {
            map.put("requiredAction", r.getRequiredAction().name());
        }
        map.put("attempts", r.getAttempts());
        map.put("durationMs", r.getDurationMs());

        FaceFrame frame = r.getCapturedFrame();
        if (frame != null) {
            map.put("frameWidth", frame.getWidth());
            map.put("frameHeight", frame.getHeight());
            map.put("frameFormat", frame.getFormat().name());
            map.put("frameBytesLength", frame.getData().length);
        }

        return map;
    }

    public static Map<String, Object> serializeCaptureArea(CaptureArea area) {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", area.isEnabled());
        map.put("centerX", (double) area.getCenterX());
        map.put("centerY", (double) area.getCenterY());
        map.put("radiusX", (double) area.getRadiusX());
        map.put("radiusY", (double) area.getRadiusY());
        map.put("centerTolerance", (double) area.getCenterTolerance());
        map.put("minFill", (double) area.getMinFill());
        map.put("maxFill", (double) area.getMaxFill());
        return map;
    }
}
