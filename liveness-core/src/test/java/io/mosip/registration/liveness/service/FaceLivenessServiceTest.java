package io.mosip.registration.liveness.service;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.audit.InMemoryAuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;
import io.mosip.registration.liveness.session.CameraConsent;
import io.mosip.registration.liveness.session.FailureReason;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessOutcome;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessState;
import io.mosip.registration.liveness.session.LivenessUpdate;
import io.mosip.registration.liveness.session.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** End-to-end with real threads: mock device → simulated engine → session → listener. */
@Timeout(30)
class FaceLivenessServiceTest {

    private static final LivenessPolicy FAST = LivenessPolicy.builder()
            .passiveMinFrames(5).holdStillMs(200).challengeTimeoutMs(4000).build();

    private final SimulatedSubject subject = new SimulatedSubject(System::currentTimeMillis, new Random(1));
    private final MockFaceDevice device = new MockFaceDevice(subject, 30, DeviceLevel.L1);
    private final SimulatedLivenessEngine engine = new SimulatedLivenessEngine();
    private final InMemoryAuditSink audit = new InMemoryAuditSink();
    private final FaceLivenessService service = new FaceLivenessService(LivenessConfig.uniform(FAST), engine, audit,
            new LivenessMetrics(), new ChallengeSelector(), System::currentTimeMillis);

    @Test
    void livePersonPassesAndDeviceIsReleased() throws Exception {
        subject.setLivenessLevel(58);
        subject.setReactionDelayMs(150);
        Recorder ui = new Recorder();

        LivenessHandle handle = service.start(LivenessWorkflow.OPERATOR_AUTHENTICATION, granted(), device, subject.respondingTo(ui));
        LivenessResult result = awaitWithoutFailedAttempt(handle, ui);

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertEquals(LivenessWorkflow.OPERATOR_AUTHENTICATION, result.getWorkflow());
        assertTrue(result.isActiveVerificationPerformed());
        assertTrue(ui.previewFrames > 0);
        assertEquals(DeviceStatus.DISCONNECTED, device.status());
        assertTrue(service.metrics().snapshot().framesProcessed > 0);
    }

    @Test
    void disconnectMidSessionCanBeRetriedWithoutUsingAnAttempt() throws Exception {
        Recorder ui = new Recorder();
        LivenessHandle handle = service.start(LivenessWorkflow.RESIDENT_REGISTRATION, granted(), device, subject.respondingTo(ui));
        assertTrue(ui.checking.await(5, TimeUnit.SECONDS));

        device.simulateDisconnect();
        assertTrue(ui.failed.await(5, TimeUnit.SECONDS));
        assertEquals(UserMessage.FAIL_DEVICE, ui.lastFailure.getMessage());
        assertEquals(1, ui.lastFailure.getAttempt(), "device faults do not count as attempts");

        handle.retry();
        LivenessResult result = handle.result().get(20, TimeUnit.SECONDS);
        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertEquals(FailureReason.DEVICE_DISCONNECTED, result.getAttemptFailures().get(0));
    }

    @Test
    void unavailableDeviceIsReportedAndRecoversOnRetry() throws Exception {
        device.setAvailable(false);
        Recorder ui = new Recorder();
        LivenessHandle handle = service.start(LivenessWorkflow.SUPERVISOR_AUTHENTICATION, granted(), device, subject.respondingTo(ui));
        assertTrue(ui.failed.await(5, TimeUnit.SECONDS));

        device.setAvailable(true);
        handle.retry();

        assertEquals(LivenessOutcome.PASSED, handle.result().get(20, TimeUnit.SECONDS).getOutcome());
    }

    @Test
    void persistentEngineErrorsFailTheAttempt() throws Exception {
        engine.failNext(1000);
        Recorder ui = new Recorder();
        LivenessHandle handle = service.start(LivenessWorkflow.RESIDENT_REGISTRATION, granted(), device, subject.respondingTo(ui));

        assertTrue(ui.failed.await(5, TimeUnit.SECONDS));
        assertEquals(UserMessage.FAIL_GENERIC, ui.lastFailure.getMessage());
        handle.cancel();
        assertEquals(LivenessOutcome.CANCELLED, handle.result().get(5, TimeUnit.SECONDS).getOutcome());
    }

    @Test
    void corruptFramesAreSkippedUntilTheyPersist() throws Exception {
        device.injectCorruptFrames(3);
        Recorder ui = new Recorder();
        LivenessResult result = service.start(LivenessWorkflow.RESIDENT_REGISTRATION, granted(), device, subject.respondingTo(ui))
                .result().get(20, TimeUnit.SECONDS);
        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
    }

    /** Waits for the result, failing at once (with the audited reason) if an attempt fails, since nothing retries here. */
    private LivenessResult awaitWithoutFailedAttempt(LivenessHandle handle, Recorder ui) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (handle.result().isDone()) {
                return handle.result().get();
            }
            if (ui.failed.await(50, TimeUnit.MILLISECONDS)) {
                handle.cancel();
                fail("Attempt failed: " + audit.ofType(AuditEventType.ATTEMPT_FAILED).get(0).getDetails()
                        + ", challenges " + audit.ofType(AuditEventType.CHALLENGE_ISSUED));
            }
        }
        return fail("No result within 20 s");
    }

    private static final class Recorder implements LivenessListener {
        final CountDownLatch checking = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        volatile LivenessUpdate lastFailure;
        volatile int previewFrames;

        @Override
        public void onUpdate(LivenessUpdate update) {
            if (update.getState() == LivenessState.PASSIVE_CHECK) {
                checking.countDown();
            }
            if (update.getState() == LivenessState.ATTEMPT_FAILED) {
                lastFailure = update;
                failed.countDown();
            }
        }

        @Override
        public void onComplete(LivenessResult result) {
        }

        @Override
        public void onPreviewFrame(io.mosip.registration.liveness.device.FaceFrame frame) {
            previewFrames++;
        }
    }

    private static CameraConsent granted() {
        return CameraConsent.granted("test-notice", "en", System.currentTimeMillis());
    }
}
