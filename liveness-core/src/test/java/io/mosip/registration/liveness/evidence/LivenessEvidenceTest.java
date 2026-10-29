package io.mosip.registration.liveness.evidence;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.InMemoryAuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.gate.GateListener;
import io.mosip.registration.liveness.gate.GateResult;
import io.mosip.registration.liveness.gate.LivenessGate;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;
import io.mosip.registration.liveness.mock.SimulatedSubject.Presentation;
import io.mosip.registration.liveness.model.ModelSigning;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.session.CameraConsent;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivenessEvidenceTest {

    private static final byte[] FACE = "mds-signed-face-iso19794-5".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    private static LivenessEvidence.Builder passed() {
        return LivenessEvidence.builder()
                .sessionId("s-1").workflow("RESIDENT_CAPTURE").outcome("PASSED").gateStatus("ACCEPTED")
                .passiveScore(64.5f).activeVerificationPerformed(true).challenges(Arrays.asList("BLINK", "TURN_LEFT"))
                .attempts(1).engine("onnx 1.0 (models 1.2.0)").modelPackage("mosip-face 1.2.0")
                .captureSha256(LivenessEvidence.sha256Hex(FACE))
                .subjectSha256(LivenessEvidence.sha256Hex("10001100010002420241029".getBytes(StandardCharsets.UTF_8)))
                .stationId("10011").issuedAt(NOW.getEpochSecond()).durationMs(5400);
    }

    @Test
    void signedEvidenceVerifiesForTheSameCaptureAndSubject() throws Exception {
        KeyPair station = ModelSigning.generateKeyPair();
        String jws = LivenessEvidenceJws.sign(passed().build(), EvidenceSigner.withKey(station.getPrivate(), station.getPublic()));

        EvidenceVerification v = LivenessEvidenceJws.verify(jws, station.getPublic(), FACE, "10001100010002420241029", NOW);

        assertTrue(v.isAccepted(), v::toString);
        LivenessEvidence e = v.getEvidence();
        assertEquals(Arrays.asList("BLINK", "TURN_LEFT"), e.getChallenges());
        assertEquals(64.5f, e.getPassiveScore(), 0.001);
        assertEquals("10011", e.getStationId());
        assertEquals("mosip-face 1.2.0", e.getModelPackage());
    }

    @Test
    void rsaStationKeysWorkToo() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair station = g.generateKeyPair();
        String jws = LivenessEvidenceJws.sign(passed().build(), EvidenceSigner.withKey(station.getPrivate(), station.getPublic()));

        assertTrue(LivenessEvidenceJws.verify(jws, station.getPublic(), FACE, null, NOW).isAccepted());
        assertTrue(header(jws).contains("\"alg\":\"RS256\""));
    }

    @Test
    void es256SignatureIsTheStandardJwsForm() throws Exception {
        // Any JOSE library must accept it: check against Java's own IEEE P1363 (R||S) verifier.
        KeyPair station = ModelSigning.generateKeyPair();
        for (int i = 0; i < 20; i++) {
            String jws = LivenessEvidenceJws.sign(passed().sessionId("s-" + i).build(),
                    EvidenceSigner.withKey(station.getPrivate(), station.getPublic()));
            String[] parts = jws.split("\\.");
            Signature p1363 = Signature.getInstance("SHA256withECDSAinP1363Format");
            p1363.initVerify(station.getPublic());
            p1363.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            byte[] raw = Base64.getUrlDecoder().decode(parts[2]);
            assertEquals(64, raw.length);
            assertTrue(p1363.verify(raw));
            assertArrayEquals(raw, LivenessEvidenceJws.derToRaw(LivenessEvidenceJws.rawToDer(raw)));
        }
    }

    @Test
    void faceSwappedAfterLivenessIsRefused() throws Exception {
        KeyPair station = ModelSigning.generateKeyPair();
        String jws = LivenessEvidenceJws.sign(passed().build(), EvidenceSigner.withKey(station.getPrivate(), station.getPublic()));

        EvidenceVerification v = LivenessEvidenceJws.verify(jws, station.getPublic(),
                "someone-else".getBytes(StandardCharsets.UTF_8), null, NOW);

        assertEquals(EvidenceVerification.Status.CAPTURE_MISMATCH, v.getStatus());
        assertEquals(EvidenceVerification.Status.CAPTURE_MISMATCH,
                LivenessEvidenceJws.verify(jws, station.getPublic(), null, null, NOW).getStatus());
    }

    @Test
    void evidenceForAnotherRegistrationIsRefused() throws Exception {
        KeyPair station = ModelSigning.generateKeyPair();
        String jws = LivenessEvidenceJws.sign(passed().build(), EvidenceSigner.withKey(station.getPrivate(), station.getPublic()));

        assertEquals(EvidenceVerification.Status.SUBJECT_MISMATCH,
                LivenessEvidenceJws.verify(jws, station.getPublic(), FACE, "10001100010009999", NOW).getStatus());
    }

    @Test
    void tamperedOrForeignEvidenceIsRefused() throws Exception {
        KeyPair station = ModelSigning.generateKeyPair();
        KeyPair other = ModelSigning.generateKeyPair();
        LivenessEvidence failed = passed().outcome("FAILED").gateStatus("LIVENESS_NOT_PASSED").build();
        String jws = LivenessEvidenceJws.sign(failed, EvidenceSigner.withKey(station.getPrivate(), station.getPublic()));
        String[] parts = jws.split("\\.");

        // Flip the outcome to PASSED and keep the original signature.
        String forgedPayload = b64(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"FAILED\"", "\"PASSED\"").replace("LIVENESS_NOT_PASSED", "ACCEPTED"));
        assertEquals(EvidenceVerification.Status.BAD_SIGNATURE, LivenessEvidenceJws.verify(
                parts[0] + "." + forgedPayload + "." + parts[2], station.getPublic(), FACE, null, NOW).getStatus());

        // Signed by a key that is not the station's.
        String foreign = LivenessEvidenceJws.sign(passed().build(), EvidenceSigner.withKey(other.getPrivate(), other.getPublic()));
        assertEquals(EvidenceVerification.Status.BAD_SIGNATURE,
                LivenessEvidenceJws.verify(foreign, station.getPublic(), FACE, null, NOW).getStatus());

        // alg "none" with no signature.
        String none = b64("{\"alg\":\"none\",\"typ\":\"" + LivenessEvidenceJws.TYPE + "\"}") + "."
                + b64(passed().build().toJson()) + ".";
        EvidenceVerification v = LivenessEvidenceJws.verify(none, station.getPublic(), FACE, null, NOW);
        assertEquals(EvidenceVerification.Status.BAD_SIGNATURE, v.getStatus());
        assertNull(v.getEvidence(), "unverified content is never handed out");

        assertEquals(EvidenceVerification.Status.MALFORMED,
                LivenessEvidenceJws.verify("not-a-jws", station.getPublic(), FACE, null, NOW).getStatus());
    }

    @Test
    void genuineFailureEvidenceIsReportedForManualReview() throws Exception {
        KeyPair station = ModelSigning.generateKeyPair();
        LivenessEvidence failed = passed().outcome("FAILED").gateStatus("LIVENESS_NOT_PASSED").captureSha256(null)
                .attemptFailures(Arrays.asList("PAD_ATTACK_DETECTED", "CHALLENGE_TIMEOUT")).build();
        String jws = LivenessEvidenceJws.sign(failed, EvidenceSigner.withKey(station.getPrivate(), station.getPublic()));

        EvidenceVerification v = LivenessEvidenceJws.verify(jws, station.getPublic(), FACE, null, NOW);

        assertEquals(EvidenceVerification.Status.LIVENESS_NOT_PASSED, v.getStatus());
        assertEquals(Arrays.asList("PAD_ATTACK_DETECTED", "CHALLENGE_TIMEOUT"), v.getEvidence().getAttemptFailures());
    }

    @Test
    void evidenceFromTheFutureIsRefused() throws Exception {
        KeyPair station = ModelSigning.generateKeyPair();
        String jws = LivenessEvidenceJws.sign(passed().issuedAt(NOW.getEpochSecond() + 3600).build(),
                EvidenceSigner.withKey(station.getPrivate(), station.getPublic()));

        assertEquals(EvidenceVerification.Status.NOT_YET_VALID,
                LivenessEvidenceJws.verify(jws, station.getPublic(), FACE, null, NOW).getStatus());
    }

    @Test
    void jsonRoundTripKeepsEveryField() {
        LivenessEvidence e = passed().passiveScore(Float.NaN).modelPackage(null).engine("eng \"quoted\" \\ name").build();

        LivenessEvidence back = LivenessEvidence.fromJson(e.toJson());

        assertEquals(e.toJson(), back.toJson());
        assertTrue(Float.isNaN(back.getPassiveScore()));
        assertNull(back.getModelPackage());
        assertEquals("eng \"quoted\" \\ name", back.getEngine());
    }

    @Test
    @Timeout(40)
    void evidenceFromARealGateRunVerifies() throws Exception {
        SimulatedSubject subject = new SimulatedSubject(System::currentTimeMillis, new Random(1));
        subject.setPresentation(Presentation.LIVE);
        MockFaceDevice device = new MockFaceDevice(subject, 30, DeviceLevel.L1);
        SimulatedLivenessEngine engine = new SimulatedLivenessEngine();
        InMemoryAuditSink audit = new InMemoryAuditSink();
        LivenessPolicy policy = LivenessPolicy.builder().passiveMinFrames(5).holdStillMs(200)
                .challengeTimeoutMs(4000).maxRetries(0).activeAlways(true).build();
        LivenessConfig config = LivenessConfig.uniform(policy);
        LivenessGate gate = new LivenessGate(new FaceLivenessService(config, engine, audit, new LivenessMetrics(),
                new ChallengeSelector(), System::currentTimeMillis), config, engine, audit);
        LivenessListener responder = subject.respondingTo(new LivenessListener() {
            @Override
            public void onUpdate(LivenessUpdate update) {
            }

            @Override
            public void onComplete(LivenessResult result) {
            }
        });

        GateResult<byte[]> result = gate.start(LivenessWorkflow.SUPERVISOR_AUTHENTICATION, "supervisor1",
                CameraConsent.granted("test", "eng", System.currentTimeMillis()), device, () -> FACE,
                new GateListener<byte[]>() {
                    @Override
                    public void onUpdate(LivenessUpdate update) {
                        responder.onUpdate(update);
                    }

                    @Override
                    public void onPreviewFrame(FaceFrame frame) {
                        responder.onPreviewFrame(frame);
                    }

                    @Override
                    public void onGateComplete(GateResult<byte[]> r) {
                    }
                }).result().get(30, TimeUnit.SECONDS);
        assertTrue(result.isAccepted(), result::toString);

        KeyPair station = ModelSigning.generateKeyPair();
        LivenessEvidence evidence = LivenessEvidence.of(result, result.getCapture(), "supervisor1", "10011", null, Instant.now());
        String jws = LivenessEvidenceJws.sign(evidence, EvidenceSigner.withKey(station.getPrivate(), station.getPublic()));
        EvidenceVerification v = LivenessEvidenceJws.verify(jws, station.getPublic(), FACE, "supervisor1", Instant.now());

        assertTrue(v.isAccepted(), v::toString);
        assertEquals("SUPERVISOR_AUTHENTICATION", v.getEvidence().getWorkflow());
        assertTrue(v.getEvidence().isActiveVerificationPerformed(), "the policy always asks for challenges");
        assertTrue(!v.getEvidence().getChallenges().isEmpty());
        assertNotNull(v.getEvidence().getSessionId());
        assertTrue(!jws.contains(Base64.getUrlEncoder().withoutPadding().encodeToString(FACE)), "no biometric data");
    }

    private static String header(String jws) {
        return new String(Base64.getUrlDecoder().decode(jws.split("\\.")[0]), StandardCharsets.UTF_8);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
