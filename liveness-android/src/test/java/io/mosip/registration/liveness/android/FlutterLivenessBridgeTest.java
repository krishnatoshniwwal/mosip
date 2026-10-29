package io.mosip.registration.liveness.android;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.android.bridge.FlutterLivenessBridge;
import io.mosip.registration.liveness.android.camera.CameraXFaceCaptureDevice;
import io.mosip.registration.liveness.audit.InMemoryAuditSink;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.CaptureArea;
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.PixelFormat;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.PadResult;
import io.mosip.registration.liveness.i18n.LivenessMessages;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.session.FailureReason;
import io.mosip.registration.liveness.session.LivenessOutcome;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessState;
import io.mosip.registration.liveness.session.LivenessUpdate;
import io.mosip.registration.liveness.session.SessionTestObjects;
import io.mosip.registration.liveness.session.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlutterLivenessBridgeTest {

    private static final EngineInfo ENGINE_INFO = new EngineInfo(
            "TestEngine", "1.0", "m1", EnumSet.allOf(ChallengeType.class), true);

    @Test
    void methodDispatchAndSerialization() {
        LivenessConfig config = LivenessConfig.defaults();
        LivenessEngine mockEngine = new LivenessEngine() {
            @Override
            public EngineInfo info() { return ENGINE_INFO; }
            @Override
            public FrameAnalysis analyze(io.mosip.registration.liveness.device.FaceFrame frame) {
                return new FrameAnalysis(Collections.emptyList(), 85f, PadResult.bonaFide(85f));
            }
        };

        FaceLivenessService service = new FaceLivenessService(config, mockEngine, new InMemoryAuditSink());
        CameraXFaceCaptureDevice device = new CameraXFaceCaptureDevice();
        FlutterLivenessBridge bridge = new FlutterLivenessBridge(service, device, config);

        AtomicReference<Object> eventReceived = new AtomicReference<>();
        bridge.setEventSink(new FlutterLivenessBridge.EventSink() {
            @Override
            public void success(Object event) { eventReceived.set(event); }
            @Override
            public void error(String code, String msg, Object details) {}
            @Override
            public void endOfStream() {}
        });

        // 1. Get status when idle
        AtomicReference<Object> methodResponse = new AtomicReference<>();
        bridge.onMethodCall("getStatus", Collections.emptyMap(), new FlutterLivenessBridge.MethodResult() {
            @Override
            public void success(Object r) { methodResponse.set(r); }
            @Override
            public void error(String c, String m, Object d) {}
            @Override
            public void notImplemented() {}
        });

        Map<?, ?> status = (Map<?, ?>) methodResponse.get();
        assertNotNull(status);
        assertEquals(false, status.get("active"));

        // 2. Start session with consent
        Map<String, Object> startArgs = new HashMap<>();
        startArgs.put("workflow", "OPERATOR_AUTHENTICATION");
        startArgs.put("consentGranted", true);

        bridge.onMethodCall("start", startArgs, new FlutterLivenessBridge.MethodResult() {
            @Override
            public void success(Object r) { methodResponse.set(r); }
            @Override
            public void error(String c, String m, Object d) {}
            @Override
            public void notImplemented() {}
        });

        Map<?, ?> startResp = (Map<?, ?>) methodResponse.get();
        assertNotNull(startResp.get("sessionId"));
        assertNotNull(startResp.get("captureArea"));

        // 3. Cancel session
        bridge.onMethodCall("cancel", Collections.emptyMap(), new FlutterLivenessBridge.MethodResult() {
            @Override
            public void success(Object r) { methodResponse.set(r); }
            @Override
            public void error(String c, String m, Object d) {}
            @Override
            public void notImplemented() {}
        });

        Map<?, ?> cancelResp = (Map<?, ?>) methodResponse.get();
        assertEquals("CANCELLED", cancelResp.get("status"));
    }

    @Test
    void serializesUpdateAndResultMaps() {
        LivenessUpdate update = SessionTestObjects.update(
                "s123", LivenessWorkflow.RESIDENT_REGISTRATION, LivenessState.ACTIVE_CHALLENGE,
                UserMessage.CHECKING_LIVENESS, UserMessage.GUIDE_FACE_IN_AREA,
                Collections.singletonList(UserMessage.GUIDE_IMPROVE_LIGHTING),
                ChallengeType.TURN_LEFT, 1, 2, 0.5, 3000L, 1, 1, 3, false
        );

        Map<String, Object> map = FlutterLivenessBridge.serializeUpdate(update);
        assertEquals("update", map.get("eventType"));
        assertEquals("s123", map.get("sessionId"));
        assertEquals("ACTIVE_CHALLENGE", map.get("state"));
        assertEquals("TURN_LEFT", map.get("challenge"));
        assertEquals(1, map.get("challengeNumber"));
        assertEquals(2, map.get("challengeCount"));
        assertEquals(3000L, map.get("remainingMs"));
        assertEquals(0.5, map.get("progress"));
        assertFalse((Boolean) map.get("retryAvailable"));

        LivenessResult result = SessionTestObjects.result(
                "s123", LivenessWorkflow.RESIDENT_REGISTRATION, LivenessOutcome.PASSED,
                null, null, null, null, 88.5f, true,
                Collections.singletonList(ChallengeType.TURN_LEFT), 1, Collections.emptyList(),
                "Engine-v1", 1200L, 0L
        );

        Map<String, Object> resMap = FlutterLivenessBridge.serializeResult(result);
        assertEquals("complete", resMap.get("eventType"));
        assertEquals("PASSED", resMap.get("outcome"));
        assertTrue((Boolean) resMap.get("accepted"));
        assertEquals(88.5, (Double) resMap.get("passiveScore"), 0.01);
        assertEquals(List.of("TURN_LEFT"), resMap.get("challengesCompleted"));
    }

    @Test
    void updatesCarryTheScreenTextsInTheSessionLanguage() {
        LivenessUpdate update = SessionTestObjects.update(
                "s1", LivenessWorkflow.OPERATOR_AUTHENTICATION, LivenessState.ACTIVE_CHALLENGE,
                UserMessage.CHALLENGE_TURN_LEFT, UserMessage.FEEDBACK_CONTINUE,
                Collections.singletonList(UserMessage.GUIDE_IMPROVE_LIGHTING),
                ChallengeType.TURN_LEFT, 1, 2, 0.5, 5200L, 1, 2, 3, false);
        LivenessMessages french = LivenessMessages.forLanguage("fr");

        Map<String, Object> map = FlutterLivenessBridge.serializeUpdate(update, french);

        assertEquals(french.text(UserMessage.CHALLENGE_TURN_LEFT), map.get("messageText"));
        assertEquals(french.text(UserMessage.FEEDBACK_CONTINUE), map.get("guidanceText"));
        assertEquals(List.of(french.text(UserMessage.GUIDE_IMPROVE_LIGHTING)), map.get("tipsText"));
        assertEquals(french.ui(LivenessMessages.UI_CHALLENGE, 1, 2), map.get("stepText"));
        assertEquals(french.ui(LivenessMessages.UI_TIME_LEFT, 6), map.get("timeLeftText"), "5.2 s rounds up");
        assertEquals("Tentative 2 sur 3", map.get("attemptText"));
        assertEquals("CHALLENGE_TURN_LEFT", map.get("messageCode"), "codes and English stay for other callers");
        assertEquals(UserMessage.CHALLENGE_TURN_LEFT.defaultText(), map.get("message"));
    }

    @Test
    void stepAndAttemptAreOnlySentWhenTheyApply() {
        LivenessUpdate passive = SessionTestObjects.update(
                "s1", LivenessWorkflow.RESIDENT_REGISTRATION, LivenessState.PASSIVE_CHECK,
                UserMessage.CHECKING_LIVENESS, null, Collections.emptyList(),
                null, 0, 0, 0.2, -1L, 1, 1, 3, false);
        LivenessUpdate done = SessionTestObjects.update(
                "s1", LivenessWorkflow.RESIDENT_REGISTRATION, LivenessState.PASSED,
                UserMessage.SUCCESS, null, Collections.emptyList(),
                null, 0, 0, 1.0, -1L, 1, 1, 3, false);

        Map<String, Object> p = FlutterLivenessBridge.serializeUpdate(passive, LivenessMessages.english());
        Map<String, Object> d = FlutterLivenessBridge.serializeUpdate(done, LivenessMessages.english());

        assertNull(p.get("stepText"));
        assertNull(p.get("timeLeftText"));
        assertNull(p.get("guidanceText"));
        assertEquals("Attempt 1 of 3", p.get("attemptText"));
        assertNull(d.get("attemptText"), "no attempt counter once the session is over");
    }

    @Test
    void acceptedResultCarriesTheVerifiedFaceAndAFailedOneDoesNot() {
        FaceFrame frame = new FaceFrame("cam", 1, 0, 2, 2, PixelFormat.NV21, new byte[6], Collections.emptyMap());
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        FlutterLivenessBridge.FrameEncoder encoder = f -> jpeg;
        LivenessResult passed = SessionTestObjects.result("s1", LivenessWorkflow.RESIDENT_REGISTRATION,
                LivenessOutcome.PASSED, null, null, frame, null, 90f, false, Collections.emptyList(), 1,
                Collections.emptyList(), "e", 900L, 0L);
        LivenessResult locked = SessionTestObjects.result("s2", LivenessWorkflow.SUPERVISOR_AUTHENTICATION,
                LivenessOutcome.FAILED, FailureReason.LOCKED_OUT, FailureAction.BLOCK, frame, null, Float.NaN, false,
                Collections.emptyList(), 0, Collections.emptyList(), "e", 10L, 1_900_000_000_000L);

        Map<String, Object> ok = FlutterLivenessBridge.serializeResult(passed, LivenessMessages.english(), encoder);
        Map<String, Object> no = FlutterLivenessBridge.serializeResult(locked, LivenessMessages.english(), encoder);

        assertArrayEquals(jpeg, (byte[]) ok.get("capturedJpeg"));
        assertNull(no.get("capturedJpeg"), "a face that failed liveness is never handed back");
        assertEquals(1_900_000_000_000L, no.get("lockedUntil"));
        assertNull(ok.get("lockedUntil"));
    }

    @Test
    void aFailingEncoderStillDeliversTheResult() {
        FaceFrame frame = new FaceFrame("cam", 1, 0, 2, 2, PixelFormat.NV21, new byte[6], Collections.emptyMap());
        LivenessResult passed = SessionTestObjects.result("s1", LivenessWorkflow.RESIDENT_REGISTRATION,
                LivenessOutcome.PASSED, null, null, frame, null, 90f, false, Collections.emptyList(), 1,
                Collections.emptyList(), "e", 900L, 0L);

        Map<String, Object> map = FlutterLivenessBridge.serializeResult(passed, LivenessMessages.english(), f -> {
            throw new IllegalStateException("encoder broke");
        });

        assertTrue((Boolean) map.get("accepted"));
        assertNull(map.get("capturedJpeg"));
    }

    @Test
    void startSendsTheScreenLabelsAndLayoutDirection() {
        FaceLivenessService service = new FaceLivenessService(LivenessConfig.defaults(), idleEngine(), new InMemoryAuditSink());
        FlutterLivenessBridge bridge = new FlutterLivenessBridge(service, new CameraXFaceCaptureDevice(),
                LivenessConfig.defaults());
        Map<String, Object> args = new HashMap<>();
        args.put("workflow", "SUPERVISOR_AUTHENTICATION");
        args.put("subjectKey", "110012");
        args.put("language", "ar");
        args.put("consentGranted", true);

        Map<?, ?> response = (Map<?, ?>) call(bridge, "start", args);
        call(bridge, "cancel", Collections.emptyMap());

        assertEquals(true, response.get("rtl"));
        assertEquals("ar", response.get("language"));
        Map<?, ?> ui = (Map<?, ?>) response.get("ui");
        LivenessMessages arabic = LivenessMessages.forLanguage("ar");
        assertEquals(arabic.ui(LivenessMessages.UI_RETRY), ui.get("retry"));
        assertEquals(arabic.ui(LivenessMessages.UI_CONSENT_TEXT), ui.get("consentText"));
        assertEquals(arabic.text(UserMessage.FAIL_CONSENT), ui.get("consentFailed"));
        assertEquals(arabic.ui(LivenessMessages.UI_ON_DEVICE), ui.get("onDevice"));
    }

    @Test
    void aNewStartReplacesAnAbandonedSession() {
        FaceLivenessService service = new FaceLivenessService(LivenessConfig.defaults(), idleEngine(), new InMemoryAuditSink());
        FlutterLivenessBridge bridge = new FlutterLivenessBridge(service, new CameraXFaceCaptureDevice(),
                LivenessConfig.defaults());
        Map<String, Object> args = new HashMap<>();
        args.put("workflow", "OPERATOR_AUTHENTICATION");
        args.put("consentGranted", true);

        String first = (String) ((Map<?, ?>) call(bridge, "start", args)).get("sessionId");
        String second = (String) ((Map<?, ?>) call(bridge, "start", args)).get("sessionId");
        Map<?, ?> status = (Map<?, ?>) call(bridge, "getStatus", Collections.emptyMap());
        call(bridge, "cancel", Collections.emptyMap());

        assertNotEquals(first, second);
        assertEquals(second, status.get("sessionId"));
    }

    private static LivenessEngine idleEngine() {
        return new LivenessEngine() {
            @Override
            public EngineInfo info() { return ENGINE_INFO; }
            @Override
            public FrameAnalysis analyze(FaceFrame frame) {
                return new FrameAnalysis(Collections.emptyList(), 85f, PadResult.bonaFide(85f));
            }
        };
    }

    private static Object call(FlutterLivenessBridge bridge, String method, Map<String, Object> args) {
        AtomicReference<Object> out = new AtomicReference<>();
        bridge.onMethodCall(method, args, new FlutterLivenessBridge.MethodResult() {
            @Override
            public void success(Object r) { out.set(r); }
            @Override
            public void error(String c, String m, Object d) { out.set(c + ": " + m); }
            @Override
            public void notImplemented() { out.set("not implemented"); }
        });
        return out.get();
    }

    @Test
    void serializesCaptureArea() {
        CaptureArea area = new CaptureArea(true, 0.5f, 0.48f, 0.22f, 0.37f, 0.45f, 0.4f, 1.15f);
        Map<String, Object> map = FlutterLivenessBridge.serializeCaptureArea(area);
        assertEquals(true, map.get("enabled"));
        assertEquals(0.5, (Double) map.get("centerX"), 0.001);
        assertEquals(0.48, (Double) map.get("centerY"), 0.001);
        assertEquals(0.22, (Double) map.get("radiusX"), 0.001);
    }
}
