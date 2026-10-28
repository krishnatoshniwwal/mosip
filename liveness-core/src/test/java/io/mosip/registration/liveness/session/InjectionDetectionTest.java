package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.config.VirtualCameraPolicy;
import io.mosip.registration.liveness.injection.CameraEndpoint;
import io.mosip.registration.liveness.injection.CameraSourceReport;
import io.mosip.registration.liveness.mock.SimulatedSubject.Presentation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deepfakes and recordings injected through a virtual camera, which presentation attack detection cannot see. */
class InjectionDetectionTest {

    private static final LivenessPolicy DEFAULT = LivenessPolicy.builder().build();
    private static final CameraEndpoint WEBCAM =
            new CameraEndpoint("Integrated Camera", "USB\\VID_5986&PID_2174&MI_00\\6&3B5246B5&1&0000", true);

    /** The reason this check exists: an injected deepfake does everything a live person does. */
    @Test
    void injectedDeepfakePassesPadAndChallengesWhenInjectionDetectionIsOff() {
        SimulationHarness h = new SimulationHarness(1);
        h.subject.setPresentation(Presentation.INJECTED_STREAM);
        h.newSession(DEFAULT.toBuilder().injectionEnabled(false).activeAlways(true).build(), 1);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertTrue(result.isActiveVerificationPerformed());
    }

    @Test
    void injectedDeepfakeIsRejectedFromItsFrames() {
        SimulationHarness h = new SimulationHarness(1);
        h.subject.setPresentation(Presentation.INJECTED_STREAM);
        h.newSession(DEFAULT.toBuilder().activeAlways(true).build(), 1);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.FAILED, result.getOutcome());
        assertEquals(FailureReason.MAX_RETRIES_EXCEEDED, result.getFailureReason());
        assertEquals(Collections.nCopies(DEFAULT.getMaxRetries() + 1, FailureReason.INJECTION_DETECTED),
                result.getAttemptFailures());
        assertNull(result.getCapturedFrame());
        AuditEvent first = h.audit.ofType(AuditEventType.INJECTION_DETECTED).get(0);
        assertTrue(first.getDetails().get("cues").contains("REPEATED_FRAMES"), first.getDetails().toString());
        assertEquals("PASSIVE_CHECK", first.getDetails().get("phase"), "caught before any challenge is issued");
        assertEquals(DEFAULT.getMaxRetries() + 1, h.metrics.snapshot().injectionDetections);
        assertEquals(0, h.metrics.snapshot().padDetections, "PAD saw nothing wrong");
        LivenessUpdate failed = h.updatesIn(LivenessState.ATTEMPT_FAILED).get(0);
        assertEquals(UserMessage.FAIL_GENERIC, failed.getMessage(), "the attack type is never shown");
    }

    @Test
    void livePersonInFrontOfARealCameraIsNotFlagged() {
        SimulationHarness h = new SimulationHarness(2);
        h.subject.setLivenessLevel(58);
        h.newSession(DEFAULT, 2);

        LivenessResult result = h.startAndRun();

        assertEquals(LivenessOutcome.PASSED, result.getOutcome());
        assertTrue(h.audit.ofType(AuditEventType.INJECTION_DETECTED).isEmpty());
    }

    @Test
    void reachableVirtualCameraBlocksTheSessionBeforeTheCameraIsUsed() {
        SimulationHarness h = new SimulationHarness(3);
        h.newSession(DEFAULT, 3);
        h.session.setCameraSourceReport(CameraSourceReport.of("windows",
                Arrays.asList(WEBCAM, new CameraEndpoint("OBS Virtual Camera", "SWD\\VCAMDEVAPI\\{8F2A}", true))));

        h.start();

        assertEquals(LivenessState.FAILED, h.session.state());
        assertEquals(FailureReason.CAMERA_NOT_TRUSTED, h.result.getFailureReason());
        assertEquals(FailureAction.BLOCK, h.result.getRequiredAction());
        assertEquals(0, h.result.getAttempts(), "no attempt was started");
        assertTrue(h.updatesIn(LivenessState.PASSIVE_CHECK).isEmpty());
        assertEquals(UserMessage.FAIL_CAMERA_NOT_TRUSTED, h.updates.get(h.updates.size() - 1).getMessage());
        AuditEvent e = h.audit.ofType(AuditEventType.INJECTION_DETECTED).get(0);
        assertEquals("[VIRTUAL_CAMERA]", e.getDetails().get("cues"));
        assertTrue(e.getDetails().get("cameras").contains("OBS Virtual Camera"));
    }

    @Test
    void directShowOnlyVirtualCameraIsAuditedButAllowedUnlessThePolicySaysInstalled() {
        CameraSourceReport obsInstalled = CameraSourceReport.of("windows",
                Arrays.asList(WEBCAM, new CameraEndpoint("OBS Virtual Camera", "DSHOW\\{A3FC}", false)));

        SimulationHarness h = new SimulationHarness(4);
        h.newSession(DEFAULT, 4);
        h.session.setCameraSourceReport(obsInstalled);
        assertEquals(LivenessOutcome.PASSED, h.startAndRun().getOutcome());
        String checked = h.audit.ofType(AuditEventType.CAMERA_SOURCE_CHECKED).get(0).getDetails().get("result");
        assertTrue(checked.contains("OBS Virtual Camera"), checked);

        SimulationHarness strict = new SimulationHarness(4);
        strict.newSession(DEFAULT.toBuilder().virtualCameraPolicy(VirtualCameraPolicy.BLOCK_INSTALLED).build(), 4);
        strict.session.setCameraSourceReport(obsInstalled);
        strict.start();
        assertEquals(FailureReason.CAMERA_NOT_TRUSTED, strict.result.getFailureReason());
    }

    @Test
    void auditPolicyOnlyRecordsTheVirtualCamera() {
        SimulationHarness h = new SimulationHarness(5);
        h.newSession(DEFAULT.toBuilder().virtualCameraPolicy(VirtualCameraPolicy.AUDIT).build(), 5);
        h.session.setCameraSourceReport(CameraSourceReport.of("linux", Collections.singletonList(
                new CameraEndpoint("Dummy video device", "/sys/devices/virtual/video4linux/video4", true))));

        assertEquals(LivenessOutcome.PASSED, h.startAndRun().getOutcome());
        assertFalse(h.audit.ofType(AuditEventType.CAMERA_SOURCE_CHECKED).isEmpty());
        assertTrue(h.audit.ofType(AuditEventType.INJECTION_DETECTED).isEmpty());
    }

    @Test
    void failedListingDoesNotStopTheSession() {
        SimulationHarness h = new SimulationHarness(6);
        h.newSession(DEFAULT, 6);
        h.session.setCameraSourceReport(CameraSourceReport.failed("listing failed: timeout"));

        assertEquals(LivenessOutcome.PASSED, h.startAndRun().getOutcome());
        assertEquals("listing failed: timeout",
                h.audit.ofType(AuditEventType.CAMERA_SOURCE_CHECKED).get(0).getDetails().get("result"));
    }
}
