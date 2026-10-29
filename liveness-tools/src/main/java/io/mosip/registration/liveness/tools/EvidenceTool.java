package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.evidence.EvidenceVerification;
import io.mosip.registration.liveness.evidence.LivenessEvidence;
import io.mosip.registration.liveness.evidence.LivenessEvidenceJws;
import io.mosip.registration.liveness.model.ModelSigning;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * {@code evidence verify} subcommand: the server-side check of a packet's signed liveness evidence, for testing
 * and for adjudicators. Exit status 0 only when the evidence is accepted.
 */
final class EvidenceTool {

    private EvidenceTool() {
    }

    static int run(String[] a) throws Exception {
        if (a.length < 3 || !"verify".equals(a[0])) {
            System.err.println("evidence verify <evidence.jws> <station-public.pem> [--capture face.bin] [--subject RID]");
            return 2;
        }
        String jws = new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.US_ASCII).trim();
        String capture = LivenessTools.option(a, "--capture", null);
        EvidenceVerification v = LivenessEvidenceJws.verify(jws, ModelSigning.readPublicKey(Paths.get(a[2])),
                capture == null ? null : Files.readAllBytes(Paths.get(capture)),
                LivenessTools.option(a, "--subject", null), Instant.now());
        System.out.println(v);
        LivenessEvidence e = v.getEvidence();
        if (e != null) {
            System.out.println("  station   " + e.getStationId() + ", issued " + Instant.ofEpochSecond(e.getIssuedAt()));
            System.out.println("  workflow  " + e.getWorkflow() + ", outcome " + e.getOutcome() + ", gate " + e.getGateStatus());
            System.out.println("  passive   " + e.getPassiveScore() + ", challenges " + e.getChallenges()
                    + ", attempts " + e.getAttempts() + " " + e.getAttemptFailures());
            System.out.println("  engine    " + e.getEngine());
            if (capture == null && e.getCaptureSha256() != null) {
                System.out.println("  (no --capture given: pass the packet's face to check the evidence names it)");
            }
        }
        return v.isAccepted() ? 0 : 1;
    }
}
