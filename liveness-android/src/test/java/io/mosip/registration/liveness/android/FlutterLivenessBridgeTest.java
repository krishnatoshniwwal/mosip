package io.mosip.registration.liveness.android;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.android.bridge.FlutterLivenessBridge;
import io.mosip.registration.liveness.android.camera.CameraXFaceCaptureDevice;
import io.mosip.registration.liveness.audit.InMemoryAuditSink;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.CaptureArea;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.PadResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void serializesCaptureArea() {
        CaptureArea area = new CaptureArea(true, 0.5f, 0.48f, 0.22f, 0.37f, 0.45f, 0.4f, 1.15f);
        Map<String, Object> map = FlutterLivenessBridge.serializeCaptureArea(area);
        assertEquals(true, map.get("enabled"));
        assertEquals(0.5, (Double) map.get("centerX"), 0.001);
        assertEquals(0.48, (Double) map.get("centerY"), 0.001);
        assertEquals(0.22, (Double) map.get("radiusX"), 0.001);
    }
}
