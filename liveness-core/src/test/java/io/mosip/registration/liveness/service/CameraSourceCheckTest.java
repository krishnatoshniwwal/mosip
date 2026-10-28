package io.mosip.registration.liveness.service;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.InMemoryAuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.injection.CameraEndpoint;
import io.mosip.registration.liveness.injection.CameraSourceReport;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;
import io.mosip.registration.liveness.session.AttemptRateLimiter;
import io.mosip.registration.liveness.session.CameraConsent;
import io.mosip.registration.liveness.session.FailureReason;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessOutcome;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessUpdate;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The station's cameras are checked before the device is touched, and a failing check never blocks the session. */
class CameraSourceCheckTest {

    private static final LivenessPolicy FAST = LivenessPolicy.builder().passiveMinFrames(5).holdStillMs(200).build();

    private final SimulatedSubject subject = new SimulatedSubject(System::currentTimeMillis, new Random(7));
    private final AtomicInteger opens = new AtomicInteger();
    private final FaceCaptureDevice device = counting(new MockFaceDevice(subject, 30, DeviceLevel.L1));

    @Test
    void virtualCameraOnTheStationKeepsTheCameraClosed() throws Exception {
        FaceLivenessService service = service();
        service.setCameraSourceCheck(d -> CameraSourceReport.of("test", Collections.singletonList(
                new CameraEndpoint("OBS Virtual Camera", "SWD\\VCAMDEVAPI\\{8F2A}", true))));

        LivenessResult r = start(service);

        assertEquals(FailureReason.CAMERA_NOT_TRUSTED, r.getFailureReason());
        assertEquals(0, opens.get(), "open() must not be called");
    }

    @Test
    void checkThatThrowsDoesNotStopTheSession() throws Exception {
        FaceLivenessService service = service();
        service.setCameraSourceCheck(d -> {
            throw new IllegalStateException("powershell missing");
        });

        assertEquals(LivenessOutcome.PASSED, start(service).getOutcome());
        assertEquals(1, opens.get());
    }

    private FaceLivenessService service() {
        return new FaceLivenessService(LivenessConfig.uniform(FAST), new SimulatedLivenessEngine(), new InMemoryAuditSink(),
                new LivenessMetrics(), new ChallengeSelector(), System::currentTimeMillis, new AttemptRateLimiter());
    }

    private LivenessResult start(FaceLivenessService service) throws Exception {
        CameraConsent consent = CameraConsent.granted("camera-notice-v1", "en", System.currentTimeMillis());
        return service.start(LivenessWorkflow.OPERATOR_AUTHENTICATION, consent, device, new LivenessListener() {
            @Override
            public void onUpdate(LivenessUpdate update) {
            }

            @Override
            public void onComplete(LivenessResult result) {
            }
        }).result().get(20, TimeUnit.SECONDS);
    }

    private FaceCaptureDevice counting(MockFaceDevice inner) {
        return new FaceCaptureDevice() {
            @Override
            public DeviceInfo info() {
                return inner.info();
            }

            @Override
            public DeviceStatus status() {
                return inner.status();
            }

            @Override
            public void open() throws DeviceException {
                opens.incrementAndGet();
                inner.open();
            }

            @Override
            public void startStream(FrameListener listener) throws DeviceException {
                inner.startStream(listener);
            }

            @Override
            public void stopStream() {
                inner.stopStream();
            }

            @Override
            public void close() {
                inner.close();
            }
        };
    }
}
