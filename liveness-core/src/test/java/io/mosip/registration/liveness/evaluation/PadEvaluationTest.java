package io.mosip.registration.liveness.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadEvaluationTest {

    @TempDir
    Path tmp;

    /** 10 bona fide (scores 55..100), 10 photos (5..50), 10 replays (35..80), 1 photo with no face. */
    private static PadMetrics sample() {
        List<ScoredPresentation> p = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            p.add(new ScoredPresentation("bf" + i, null, 55 + 5 * i, false, 1, 20));
            p.add(new ScoredPresentation("ph" + i, "printed_photo", 5 + 5 * i, 5 + 5 * i < 30, 1, 20));
            p.add(new ScoredPresentation("rp" + i, "video_replay", 35 + 5 * i, false, 1, 20));
        }
        p.add(ScoredPresentation.noResponse("ph-noface", "printed_photo", 1, 20));
        return new PadMetrics(p);
    }

    @Test
    void ratesAtAThresholdArePerSpeciesWithTheWorstCaseReported() {
        PadMetrics.OperatingPoint op = sample().atThreshold("t", 70);

        // Replays >= 70: 70,75,80 → 3/10. Photos never reach 70 → 0/11. Bona fide below 70: 55,60,65 → 3/10.
        assertEquals(3, op.apcerBySpecies.get("video_replay").errors);
        assertEquals(0, op.apcerBySpecies.get("printed_photo").errors);
        assertEquals(11, op.apcerBySpecies.get("printed_photo").total, "non-responses stay in the denominator");
        assertEquals(0.3, op.apcerWorst().value(), 1e-9);
        assertEquals("video_replay", op.worstSpecies());
        assertEquals(0.3, op.bpcer.value(), 1e-9);
    }

    @Test
    void engineVerdictUsesTheEnginesOwnAttackFlag() {
        PadMetrics.OperatingPoint op = sample().engineVerdict();

        // Photos flagged when score < 30 (5..25 → 5 flagged); 5 accepted. Replays never flagged → 10/10.
        assertEquals(5, op.apcerBySpecies.get("printed_photo").errors);
        assertEquals(10, op.apcerBySpecies.get("video_replay").errors);
        assertEquals(0, op.bpcer.errors);
    }

    @Test
    void bpcerAtFixedApcerFindsTheCalibratedThreshold() {
        PadMetrics m = sample();
        PadMetrics.OperatingPoint bpcer10 = m.bpcerAtApcer(0.10);

        // APCER <= 10% needs at most one replay accepted: threshold 80 (only 80 accepted) → bona fide 55..75 rejected = 5/10.
        assertEquals(80, bpcer10.threshold, 1e-9);
        assertEquals(0.1, bpcer10.apcerWorst().value(), 1e-9);
        assertEquals(0.5, bpcer10.bpcer.value(), 1e-9);

        PadMetrics.OperatingPoint bpcer100 = m.bpcerAtApcer(0.01);
        assertEquals(0.0, bpcer100.apcerWorst().value(), 1e-9);
        assertEquals(0.6, bpcer100.bpcer.value(), 1e-9, "threshold just above 80: bona fide 55..80 rejected");
    }

    @Test
    void wilsonIntervalIsHonestAboutZeroErrors() {
        PadMetrics.Rate zeroOf50 = new PadMetrics.Rate(0, 50);
        assertEquals(0.0, zeroOf50.lower(), 1e-12);
        assertTrue(zeroOf50.upper() > 0.05 && zeroOf50.upper() < 0.09, String.valueOf(zeroOf50.upper()));
        PadMetrics.Rate half = new PadMetrics.Rate(50, 100);
        assertEquals(0.5, (half.lower() + half.upper()) / 2, 1e-9);
    }

    @Test
    void nonResponseIsReportedButNeverAccepted() {
        PadMetrics m = sample();
        assertEquals(1, m.nonResponse("printed_photo").errors);
        assertEquals(0, m.nonResponse(null).errors);
        assertEquals(10, m.atThreshold("t", 0).apcerBySpecies.get("printed_photo").errors,
                "at threshold 0 every responding photo is accepted, the no-face photo is not");
    }

    @Test
    void scansTheDatasetLayoutAndTurnsScorerErrorsIntoNonResponses() throws Exception {
        Path ds = tmp.resolve("ds");
        touch(ds.resolve("bona_fide/s1/a.jpg"));
        touch(ds.resolve("bona_fide/s1/b.PNG"));
        touch(ds.resolve("bona_fide/notes.txt"));
        touch(ds.resolve("attack/Printed_Photo/p1.jpg"));
        touch(ds.resolve("attack/video_replay/r1.mp4"));
        touch(ds.resolve("attack/video_replay/broken.mp4"));

        List<PadEvaluation.Item> items = PadEvaluation.scan(ds);
        assertEquals(5, items.size(), "non-media files are skipped");
        assertEquals("printed_photo", items.get(2).species, "species names are lower-cased");

        List<String> log = new ArrayList<>();
        PadMetrics m = PadEvaluation.run(ds, (file, id, species) -> {
            if (id.endsWith("broken.mp4")) {
                throw new IllegalStateException("cannot decode");
            }
            return new ScoredPresentation(id, species, species == null ? 90 : 20, species != null, 1, 10);
        }, log::add);

        assertEquals(1, m.nonResponse("video_replay").errors);
        assertEquals(0, m.engineVerdict().apcerWorst().errors);
        assertTrue(log.stream().anyMatch(l -> l.contains("broken.mp4") && l.contains("no response")));

        Path out = tmp.resolve("out");
        PadEvaluation.writeReport(m, out, 70, "test engine 1.0");
        String report = new String(Files.readAllBytes(out.resolve("report.md")), StandardCharsets.UTF_8);
        assertTrue(report.contains("## APCER per PAI species"), report);
        assertTrue(report.contains("| video_replay |"), report);
        assertTrue(report.contains("passive.threshold"), report);
        List<String> scores = Files.readAllLines(out.resolve("scores.csv"));
        assertEquals(6, scores.size());
        assertTrue(Files.readAllLines(out.resolve("det.csv")).get(0).startsWith("threshold,apcer_worst,bpcer"));
    }

    @Test
    void reportWithoutAttacksStillRenders() {
        List<ScoredPresentation> p = new ArrayList<>();
        p.add(new ScoredPresentation("bf", null, 90, false, 1, 10));
        PadMetrics m = new PadMetrics(p);
        assertNull(m.bpcerAtApcer(0.05));
        assertTrue(PadEvaluation.markdown(m, 70, "e").contains("No threshold reaches"));
    }

    private static void touch(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {0});
    }
}
