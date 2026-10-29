package io.mosip.registration.liveness.regclient;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.InMemoryAuditSink;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.evidence.EvidenceSigner;
import io.mosip.registration.liveness.evidence.EvidenceVerification;
import io.mosip.registration.liveness.evidence.LivenessEvidenceJws;
import io.mosip.registration.liveness.gate.GateListener;
import io.mosip.registration.liveness.gate.GateResult;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;
import io.mosip.registration.liveness.model.ModelSigning;
import io.mosip.registration.liveness.session.CameraConsent;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The resident capture's signed liveness evidence, as the packet would carry it and the server would check it. */
@Timeout(40)
class RegClientEvidenceTest {

    private static final byte[] FACE = "rcapture-bioValue".getBytes(StandardCharsets.UTF_8);
    private static final String RID = "10001100010002420241029";

    @Test
    void residentCaptureCarriesEvidenceTheServerAccepts() throws Exception {
        SimulatedSubject subject = new SimulatedSubject(System::currentTimeMillis, new Random(3));
        LivenessPolicy policy = LivenessPolicy.builder().passiveMinFrames(5).holdStillMs(200)
                .challengeTimeoutMs(4000).maxRetries(0).build();
        try (RegClientLiveness liveness = new RegClientLiveness(LivenessConfig.uniform(policy),
                new SimulatedLivenessEngine(), new InMemoryAuditSink(), null)) {
            GateResult<byte[]> r = capture(liveness, subject);
            assertTrue(r.isAccepted(), r::toString);
            assertNull(liveness.issueEvidence(r, FACE, RID), "no signer configured, no evidence");

            KeyPair machineKey = ModelSigning.generateKeyPair();
            liveness.setEvidenceSigner(EvidenceSigner.withKey(machineKey.getPrivate(), machineKey.getPublic()), "10011");
            String jws = liveness.issueEvidence(r, r.getCapture(), RID);

            EvidenceVerification v = LivenessEvidenceJws.verify(jws, machineKey.getPublic(), FACE, RID, Instant.now());
            assertTrue(v.isAccepted(), v::toString);
            assertEquals("RESIDENT_REGISTRATION", v.getEvidence().getWorkflow());
            assertEquals("10011", v.getEvidence().getStationId());
        }
    }

    private static GateResult<byte[]> capture(RegClientLiveness liveness, SimulatedSubject subject) throws Exception {
        LivenessListener responder = subject.respondingTo(new LivenessListener() {
            @Override
            public void onUpdate(LivenessUpdate update) {
            }

            @Override
            public void onComplete(LivenessResult result) {
            }
        });
        return liveness.verifyThenCapture(LivenessWorkflow.RESIDENT_REGISTRATION, RID,
                CameraConsent.granted("test", "eng", System.currentTimeMillis()),
                new MockFaceDevice(subject, 30, DeviceLevel.L1), () -> FACE, new GateListener<byte[]>() {
                    @Override
                    public void onUpdate(LivenessUpdate update) {
                        responder.onUpdate(update);
                    }

                    @Override
                    public void onPreviewFrame(FaceFrame frame) {
                        responder.onPreviewFrame(frame);
                    }

                    @Override
                    public void onGateComplete(GateResult<byte[]> result) {
                    }
                }).result().get(30, TimeUnit.SECONDS);
    }
}
