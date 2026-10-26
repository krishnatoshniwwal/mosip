package io.mosip.registration.liveness.gate;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.audit.InMemoryAuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;
import io.mosip.registration.liveness.mock.SimulatedSubject.Presentation;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.session.CameraConsent;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessUpdate;
import io.mosip.registration.liveness.session.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end with real threads: liveness on the mock device, then a capture step watched on the same stream. */
@Timeout(40)
class LivenessGateTest {

    /** A fresh builder each time: builders are mutable. */
    private static LivenessPolicy.Builder fast() {
        return LivenessPolicy.builder().passiveMinFrames(5).holdStillMs(200).challengeTimeoutMs(4000).maxRetries(0);
    }

    private final SimulatedSubject subject = new SimulatedSubject(System::currentTimeMillis, new Random(1));
    private final MockFaceDevice device = new MockFaceDevice(subject, 30, DeviceLevel.L1);
    private final SimulatedLivenessEngine engine = new SimulatedLivenessEngine();
    private final InMemoryAuditSink audit = new InMemoryAuditSink();
    private final AtomicInteger captures = new AtomicInteger();

    @Test
    void capturedOnlyAfterLivenessAndAcceptedWhenThePersonStays() throws Exception {
        LivenessGate gate = gate(fast().build());
        Recorder ui = new Recorder();

        GateResult<String> r = run(gate, () -> {
            captures.incrementAndGet();
            Thread.sleep(400);
            return "rcapture";
        }, ui);

        assertEquals(GateStatus.ACCEPTED, r.getStatus(), r::toString);
        assertEquals("rcapture", r.getCapture());
        assertEquals(1, ui.captureStarted.get());
        assertTrue(r.getFramesWatched() > 0, "the capture was watched");
        assertTrue(ui.previewDuringCapture.get() > 0, "the preview keeps running while the capture runs");
        assertEquals(UserMessage.SUCCESS, r.getMessage());
        assertEquals(DeviceStatus.DISCONNECTED, device.status());
        assertTrue(!audit.ofType(AuditEventType.CAPTURE_BOUND).isEmpty());
    }

    @Test
    void captureNeverRunsWhenLivenessFails() throws Exception {
        subject.setPresentation(Presentation.PRINTED_PHOTO);

        GateResult<String> r = run(gate(fast().build()), countingCapture(), new Recorder());

        assertEquals(GateStatus.LIVENESS_NOT_PASSED, r.getStatus());
        assertEquals(0, captures.get());
        assertNull(r.getCapture());
        assertEquals(DeviceStatus.DISCONNECTED, device.status());
    }

    @Test
    void photoSwappedInDuringCaptureIsRejected() throws Exception {
        GateResult<String> r = run(gate(fast().build()), swapDuringCapture(Presentation.PRINTED_PHOTO), new Recorder());

        assertBroken(r, BindingViolation.ATTACK_DETECTED);
    }

    @Test
    void secondPersonDuringCaptureIsRejected() throws Exception {
        GateResult<String> r = run(gate(fast().build()), swapDuringCapture(Presentation.TWO_FACES), new Recorder());

        assertBroken(r, BindingViolation.MULTIPLE_FACES);
    }

    @Test
    void personLeavingDuringCaptureIsRejected() throws Exception {
        GateResult<String> r = run(gate(fast().build()), () -> {
            subject.setPresentation(Presentation.NO_FACE);
            Thread.sleep(BindingMonitor.MAX_FACE_LOSS_MS + 700);
            subject.setPresentation(Presentation.LIVE);
            return "rcapture";
        }, new Recorder());

        assertBroken(r, BindingViolation.FACE_LOST);
    }

    @Test
    void faceMovingAwayFromTheVerifiedPositionIsRejected() throws Exception {
        GateResult<String> r = run(gate(fast().build()), () -> {
            subject.setPlacement(0.85f, 0.48f, 1f);
            Thread.sleep(600);
            return "rcapture";
        }, new Recorder());

        assertBroken(r, BindingViolation.FACE_MOVED);
    }

    @Test
    void personGoneAfterAFastCaptureIsNotConfirmed() throws Exception {
        // The capture returns at once, before any violation could be seen; the person must still be there after it.
        GateResult<String> r = run(gate(fast().build()), () -> {
            subject.setPresentation(Presentation.NO_FACE);
            return "rcapture";
        }, new Recorder());

        assertEquals(GateStatus.BINDING_BROKEN, r.getStatus(), r::toString);
        assertNull(r.getCapture());
    }

    @Test
    void bindingFailuresCountTowardsTheRateLimit() throws Exception {
        LivenessPolicy policy = fast().rateLimitMaxFailures(1).build();
        FaceLivenessService service = service(policy);
        LivenessGate gate = new LivenessGate(service, LivenessConfig.uniform(policy), engine, audit);

        run(gate, swapDuringCapture(Presentation.PRINTED_PHOTO), new Recorder());

        String key = FaceLivenessService.rateLimitKey(LivenessWorkflow.SUPERVISOR_AUTHENTICATION, "supervisor1");
        assertTrue(service.rateLimiter().lockedUntil(key, System.currentTimeMillis()) > 0);
        assertTrue(!audit.ofType(AuditEventType.RATE_LIMIT_LOCKOUT).isEmpty());
    }

    @Test
    void failingCaptureIsReportedAsADeviceFailure() throws Exception {
        GateResult<String> r = run(gate(fast().build()), () -> {
            throw new IOException("MDS rCapture failed");
        }, new Recorder());

        assertEquals(GateStatus.CAPTURE_FAILED, r.getStatus());
        assertEquals("MDS rCapture failed", r.getError().getMessage());
        assertEquals(UserMessage.FAIL_DEVICE, r.getMessage());
        assertTrue(!audit.ofType(AuditEventType.CAPTURE_FAILED).isEmpty());
        assertEquals(DeviceStatus.DISCONNECTED, device.status());
    }

    @Test
    void slowCaptureTimesOut() throws Exception {
        GateResult<String> r = run(gate(fast().captureBindTimeoutMs(500).build()), () -> {
            Thread.sleep(10_000);
            return "late";
        }, new Recorder());

        assertEquals(GateStatus.CAPTURE_TIMEOUT, r.getStatus());
        assertNull(r.getCapture());
    }

    @Test
    void cancelInterruptsTheCapture() throws Exception {
        LivenessGate gate = gate(fast().build());
        Recorder ui = new Recorder();
        CountDownLatch inCapture = new CountDownLatch(1);
        GateHandle<String> h = start(gate, () -> {
            inCapture.countDown();
            Thread.sleep(10_000);
            return "never";
        }, ui);

        assertTrue(inCapture.await(20, TimeUnit.SECONDS));
        h.cancel();

        GateResult<String> r = h.result().get(10, TimeUnit.SECONDS);
        assertEquals(GateStatus.CANCELLED, r.getStatus());
        assertEquals(DeviceStatus.DISCONNECTED, device.status());
    }

    @Test
    void withBindingOffTheDeviceIsReleasedBeforeTheCapture() throws Exception {
        AtomicReference<DeviceStatus> during = new AtomicReference<>();
        GateResult<String> r = run(gate(fast().captureBindEnabled(false).build()), () -> {
            during.set(device.status());
            return "rcapture";
        }, new Recorder());

        assertEquals(GateStatus.ACCEPTED, r.getStatus());
        assertEquals(DeviceStatus.DISCONNECTED, during.get(), "the MDS gets the device to itself");
        assertEquals(0, r.getFramesWatched());
    }

    private void assertBroken(GateResult<String> r, BindingViolation expected) {
        assertEquals(GateStatus.BINDING_BROKEN, r.getStatus(), r::toString);
        assertEquals(expected, r.getViolation());
        assertNull(r.getCapture(), "a capture of the wrong person is never handed out");
        assertEquals(UserMessage.FAIL_GENERIC, r.getMessage(), "no detail about what was detected");
        assertTrue(!audit.ofType(AuditEventType.CAPTURE_BINDING_BROKEN).isEmpty());
        assertEquals(DeviceStatus.DISCONNECTED, device.status());
    }

    private CaptureStep<String> countingCapture() {
        return () -> {
            captures.incrementAndGet();
            return "rcapture";
        };
    }

    private CaptureStep<String> swapDuringCapture(Presentation presentation) {
        return () -> {
            Thread.sleep(150);
            subject.setPresentation(presentation);
            Thread.sleep(800);
            return "rcapture";
        };
    }

    private LivenessGate gate(LivenessPolicy policy) {
        return new LivenessGate(service(policy), LivenessConfig.uniform(policy), engine, audit);
    }

    private FaceLivenessService service(LivenessPolicy policy) {
        return new FaceLivenessService(LivenessConfig.uniform(policy), engine, audit, new LivenessMetrics(),
                new ChallengeSelector(), System::currentTimeMillis);
    }

    private GateResult<String> run(LivenessGate gate, CaptureStep<String> capture, Recorder ui) throws Exception {
        return start(gate, capture, ui).result().get(30, TimeUnit.SECONDS);
    }

    private GateHandle<String> start(LivenessGate gate, CaptureStep<String> capture, Recorder ui) {
        CameraConsent consent = CameraConsent.granted("test", "eng", System.currentTimeMillis());
        LivenessListener responder = subject.respondingTo(ui);
        return gate.start(LivenessWorkflow.SUPERVISOR_AUTHENTICATION, "supervisor1", consent, device, capture,
                new GateListener<String>() {
                    @Override
                    public void onUpdate(LivenessUpdate update) {
                        responder.onUpdate(update);
                    }

                    @Override
                    public void onPreviewFrame(FaceFrame frame) {
                        responder.onPreviewFrame(frame);
                    }

                    @Override
                    public void onCaptureStarted() {
                        ui.captureStarted.incrementAndGet();
                    }

                    @Override
                    public void onGateComplete(GateResult<String> result) {
                    }
                });
    }

    private static final class Recorder implements LivenessListener {
        final AtomicInteger captureStarted = new AtomicInteger();
        final AtomicInteger previewDuringCapture = new AtomicInteger();

        @Override
        public void onUpdate(LivenessUpdate update) {
        }

        @Override
        public void onComplete(LivenessResult result) {
        }

        @Override
        public void onPreviewFrame(FaceFrame frame) {
            if (captureStarted.get() > 0) {
                previewDuringCapture.incrementAndGet();
            }
        }
    }
}
