package io.mosip.registration.liveness.service;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.audit.InMemoryAuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
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
import io.mosip.registration.liveness.session.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The camera is turned on only after the person explicitly allows it. */
class CameraConsentTest {

    private static final LivenessPolicy FAST = LivenessPolicy.builder()
            .passiveMinFrames(5).holdStillMs(200).retryExhaustedAction(FailureAction.REQUIRE_SUPERVISOR_OVERRIDE).build();

    private final SimulatedSubject subject = new SimulatedSubject(System::currentTimeMillis, new Random(7));
    private final CountingDevice device = new CountingDevice(new MockFaceDevice(subject, 30, DeviceLevel.L1));
    private final InMemoryAuditSink audit = new InMemoryAuditSink();
    private final List<LivenessUpdate> updates = new CopyOnWriteArrayList<>();

    private FaceLivenessService service(LivenessPolicy policy) {
        return new FaceLivenessService(LivenessConfig.uniform(policy), new SimulatedLivenessEngine(), audit,
                new LivenessMetrics(), new ChallengeSelector(), System::currentTimeMillis, new AttemptRateLimiter());
    }

    @Test
    void cameraIsNeverOpenedWithoutConsent() throws Exception {
        LivenessResult r = service(FAST).start(LivenessWorkflow.RESIDENT_REGISTRATION, device, ui())
                .result().get(5, TimeUnit.SECONDS);

        assertEquals(LivenessOutcome.FAILED, r.getOutcome());
        assertEquals(FailureReason.CONSENT_DECLINED, r.getFailureReason());
        assertEquals(FailureAction.REQUIRE_SUPERVISOR_OVERRIDE, r.getRequiredAction(), "workflow's recovery path");
        assertEquals(0, device.opens.get(), "open() must not be called");
        assertEquals(0, device.streams.get());
        assertNull(r.getCapturedFrame());
        assertEquals(UserMessage.FAIL_CONSENT, updates.get(updates.size() - 1).getMessage());
        assertEquals("not asked", only(AuditEventType.CONSENT_NOT_GIVEN).getDetails().get("reason"));
    }

    /**
     * Sessions that end inside start must still start and complete normally. Guards the ordering in
     * LivenessHandle.begin() (ticker scheduled before the start task); the old race was too rare to hit reliably.
     */
    @Test
    void sessionsEndingAtStartNeverFailToStart() throws Exception {
        FaceLivenessService service = service(FAST);
        for (int i = 0; i < 100; i++) {
            CameraConsent declined = CameraConsent.declined("camera-notice-v1", "en", System.currentTimeMillis());
            LivenessResult r = service.start(LivenessWorkflow.RESIDENT_REGISTRATION, declined, device, ui())
                    .result().get(5, TimeUnit.SECONDS);
            assertEquals(FailureReason.CONSENT_DECLINED, r.getFailureReason());
        }
    }

    @Test
    void declinedConsentKeepsTheCameraOffAndIsAudited() throws Exception {
        CameraConsent declined = CameraConsent.declined("camera-notice-v1", "fr", System.currentTimeMillis());
        LivenessResult r = service(FAST).start(LivenessWorkflow.OPERATOR_AUTHENTICATION, declined, device, ui())
                .result().get(5, TimeUnit.SECONDS);

        assertEquals(FailureReason.CONSENT_DECLINED, r.getFailureReason());
        assertEquals(0, device.opens.get());
        AuditEvent e = only(AuditEventType.CONSENT_NOT_GIVEN);
        assertEquals("declined", e.getDetails().get("reason"));
        assertEquals("camera-notice-v1", e.getDetails().get("noticeVersion"));
    }

    @Test
    void staleConsentMustBeAskedAgain() throws Exception {
        CameraConsent old = CameraConsent.granted("camera-notice-v1", "en", System.currentTimeMillis() - 10 * 60_000);
        LivenessResult r = service(FAST).start(LivenessWorkflow.RESIDENT_REGISTRATION, old, device, ui())
                .result().get(5, TimeUnit.SECONDS);

        assertEquals(FailureReason.CONSENT_DECLINED, r.getFailureReason());
        assertEquals("expired", only(AuditEventType.CONSENT_NOT_GIVEN).getDetails().get("reason"));
        assertEquals(0, device.opens.get());
    }

    @Test
    void grantedConsentOpensTheCameraAndIsRecorded() throws Exception {
        CameraConsent ok = CameraConsent.granted("camera-notice-v1", "ar", System.currentTimeMillis());
        LivenessResult r = service(FAST).start(LivenessWorkflow.RESIDENT_REGISTRATION, ok, device, ui())
                .result().get(20, TimeUnit.SECONDS);

        assertEquals(LivenessOutcome.PASSED, r.getOutcome());
        assertEquals(1, device.opens.get());
        AuditEvent e = only(AuditEventType.CONSENT_GRANTED);
        assertEquals("ar", e.getDetails().get("language"));
        assertTrue(audit.ofType(AuditEventType.CONSENT_GRANTED).get(0).getTimestamp()
                .isBefore(audit.ofType(AuditEventType.ATTEMPT_STARTED).get(0).getTimestamp().plusMillis(1)));
    }

    @Test
    void consentCanBeSwitchedOffByPolicy() throws Exception {
        LivenessResult r = service(FAST.toBuilder().consentRequired(false).build())
                .start(LivenessWorkflow.RESIDENT_REGISTRATION, device, ui()).result().get(20, TimeUnit.SECONDS);

        assertEquals(LivenessOutcome.PASSED, r.getOutcome());
        assertTrue(audit.ofType(AuditEventType.CONSENT_NOT_GIVEN).isEmpty());
    }

    @Test
    void lockedOutSessionDoesNotTouchTheCameraEither() throws Exception {
        AttemptRateLimiter limiter = new AttemptRateLimiter();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("resident:R1", 5, 600_000, 900_000, now);
        }
        FaceLivenessService service = new FaceLivenessService(LivenessConfig.uniform(FAST), new SimulatedLivenessEngine(),
                audit, new LivenessMetrics(), new ChallengeSelector(), System::currentTimeMillis, limiter);

        LivenessResult r = service.start(LivenessWorkflow.RESIDENT_REGISTRATION, "R1",
                CameraConsent.granted("camera-notice-v1", "en", now), device, ui()).result().get(5, TimeUnit.SECONDS);

        assertEquals(FailureReason.LOCKED_OUT, r.getFailureReason());
        assertEquals(0, device.opens.get());
    }

    @Test
    void consentSettingsAreConfigurable() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("mosip.registration.liveness.operator.consent.required", "false");
        p.setProperty("mosip.registration.liveness.consent.maxAgeMs", "60000");
        LivenessConfig config = LivenessConfig.fromProperties(p);

        assertTrue(config.policyFor(LivenessWorkflow.RESIDENT_REGISTRATION).isConsentRequired());
        assertEquals(60_000, config.policyFor(LivenessWorkflow.RESIDENT_REGISTRATION).getConsentMaxAgeMs());
        assertTrue(!config.policyFor(LivenessWorkflow.OPERATOR_AUTHENTICATION).isConsentRequired());
    }

    private AuditEvent only(AuditEventType type) {
        List<AuditEvent> events = audit.ofType(type);
        assertEquals(1, events.size(), type.toString());
        return events.get(0);
    }

    private LivenessListener ui() {
        return subject.respondingTo(new LivenessListener() {
            @Override
            public void onUpdate(LivenessUpdate update) {
                updates.add(update);
            }

            @Override
            public void onComplete(LivenessResult result) {
            }
        });
    }

    /** Counts how often the service touches the hardware. */
    private static final class CountingDevice implements FaceCaptureDevice {
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger streams = new AtomicInteger();
        private final FaceCaptureDevice delegate;

        CountingDevice(FaceCaptureDevice delegate) {
            this.delegate = delegate;
        }

        @Override public DeviceInfo info() { return delegate.info(); }
        @Override public DeviceStatus status() { return delegate.status(); }

        @Override
        public void open() throws DeviceException {
            opens.incrementAndGet();
            delegate.open();
        }

        @Override
        public void startStream(FrameListener listener) throws DeviceException {
            streams.incrementAndGet();
            delegate.startStream(new FrameListener() {
                @Override public void onFrame(FaceFrame frame) { listener.onFrame(frame); }
                @Override public void onStreamError(DeviceException error) { listener.onStreamError(error); }
                @Override public void onStreamEnded() { listener.onStreamEnded(); }
            });
        }

        @Override public void stopStream() { delegate.stopStream(); }
        @Override public void close() { delegate.close(); }
    }
}
