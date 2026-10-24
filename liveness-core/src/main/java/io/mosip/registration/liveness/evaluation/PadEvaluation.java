package io.mosip.registration.liveness.evaluation;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs a PAD evaluation over a labelled dataset and writes an ISO/IEC 30107-3 style report.
 *
 * <pre>
 * dataset/
 *   bona_fide/                 live presentations (any sub-folders)
 *   attack/printed_photo/      one folder per PAI species; the folder name is the species
 *   attack/screen_display/
 *   attack/video_replay/
 *   attack/mask/
 * </pre>
 *
 * Images ({@code .jpg .jpeg .png .bmp}) and videos ({@code .mp4 .avi .mov .mkv .webm}) are both
 * presentations; how a video becomes one score is up to the {@link Scorer}. Outputs: {@code report.md},
 * {@code det.csv} (threshold, worst-case APCER, BPCER) and {@code scores.csv} (one row per presentation,
 * file names only, no biometric data).
 */
public final class PadEvaluation {

    public static final List<String> IMAGE_EXTENSIONS = Arrays.asList("jpg", "jpeg", "png", "bmp");
    public static final List<String> VIDEO_EXTENSIONS = Arrays.asList("mp4", "avi", "mov", "mkv", "webm");

    /** Turns one dataset file into a scored presentation. */
    public interface Scorer {
        /** @param species PAI species, or null for bona fide */
        ScoredPresentation score(Path file, String id, String species) throws Exception;
    }

    /** One file of the dataset and its label. */
    public static final class Item {
        public final Path file;
        public final String id;
        public final String species;

        Item(Path file, String id, String species) {
            this.file = file;
            this.id = id;
            this.species = species;
        }
    }

    private PadEvaluation() {
    }

    /** @throws IOException if the layout is wrong (no bona_fide or attack folder) or unreadable */
    public static List<Item> scan(Path dataset) throws IOException {
        Path bonaFide = dataset.resolve("bona_fide");
        Path attack = dataset.resolve("attack");
        if (!Files.isDirectory(bonaFide) && !Files.isDirectory(attack)) {
            throw new IOException("Expected " + bonaFide + " and/or " + attack + " (see PadEvaluation javadoc)");
        }
        List<Item> items = new ArrayList<>();
        if (Files.isDirectory(bonaFide)) {
            for (Path f : media(bonaFide)) {
                items.add(new Item(f, dataset.relativize(f).toString().replace('\\', '/'), null));
            }
        }
        if (Files.isDirectory(attack)) {
            try (Stream<Path> species = Files.list(attack)) {
                for (Path dir : species.filter(Files::isDirectory).sorted().collect(Collectors.toList())) {
                    String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    for (Path f : media(dir)) {
                        items.add(new Item(f, dataset.relativize(f).toString().replace('\\', '/'), name));
                    }
                }
            }
        }
        return items;
    }

    /**
     * Scores every presentation. A scorer exception becomes a non-response (never an acceptance), so a
     * crash cannot improve the numbers.
     */
    public static PadMetrics run(Path dataset, Scorer scorer, Consumer<String> progress) throws IOException {
        List<Item> items = scan(dataset);
        List<ScoredPresentation> scored = new ArrayList<>();
        int i = 0;
        for (Item item : items) {
            i++;
            ScoredPresentation p;
            try {
                p = scorer.score(item.file, item.id, item.species);
            } catch (Exception e) {
                progress.accept("  error on " + item.id + ": " + e.getMessage() + " (counted as no response)");
                p = ScoredPresentation.noResponse(item.id, item.species, 0, 0);
            }
            scored.add(p);
            if (i % 25 == 0 || i == items.size()) {
                progress.accept(String.format(Locale.ROOT, "  %d/%d presentations", i, items.size()));
            }
        }
        return new PadMetrics(scored);
    }

    /**
     * @param configuredThreshold the deployment's {@code passive.threshold}: attacks at or above it would
     *                            skip active challenges entirely
     */
    public static void writeReport(PadMetrics m, Path outDir, double configuredThreshold, String engine)
            throws IOException {
        Files.createDirectories(outDir);
        try (Writer w = Files.newBufferedWriter(outDir.resolve("det.csv"), StandardCharsets.UTF_8)) {
            w.write("threshold,apcer_worst,bpcer\n");
            for (PadMetrics.DetPoint d : m.detCurve()) {
                w.write(String.format(Locale.ROOT, "%.3f,%.5f,%.5f%n", d.threshold, d.apcerWorst, d.bpcer));
            }
        }
        try (Writer w = Files.newBufferedWriter(outDir.resolve("scores.csv"), StandardCharsets.UTF_8)) {
            w.write("id,label,score,engine_attack,frames,ms_per_frame\n");
            for (ScoredPresentation p : m.presentations()) {
                w.write(String.format(Locale.ROOT, "%s,%s,%s,%s,%d,%.1f%n", csv(p.getId()),
                        p.isBonaFide() ? "bona_fide" : p.getSpecies(),
                        p.isResponse() ? String.format(Locale.ROOT, "%.2f", p.getScore()) : "",
                        p.isResponse() ? Boolean.toString(p.isEngineAttack()) : "", p.getFrames(), p.getMsPerFrame()));
            }
        }
        Files.write(outDir.resolve("report.md"), markdown(m, configuredThreshold, engine).getBytes(StandardCharsets.UTF_8));
    }

    static String markdown(PadMetrics m, double configuredThreshold, String engine) {
        StringBuilder r = new StringBuilder();
        r.append("# PAD evaluation report (ISO/IEC 30107-3 metrics)\n\n");
        r.append("- Engine: ").append(engine).append('\n');
        r.append("- Generated: ").append(Instant.now()).append('\n');
        r.append("- Presentations: ").append(m.bonaFideCount()).append(" bona fide, ").append(m.attackCount())
                .append(" attack (").append(m.species().isEmpty() ? "no species" : String.join(", ", m.species()))
                .append(")\n\n");
        r.append("> These are PAD subsystem error rates measured with the ISO/IEC 30107-3 metrics. They are\n"
                + "> only as representative as the dataset (devices, lighting, demographics, attack instruments)\n"
                + "> and are not a certification. Intervals are Wilson 95%. A non-response is never counted as\n"
                + "> an acceptance (see the non-response table).\n\n");

        List<PadMetrics.OperatingPoint> points = new ArrayList<>();
        points.add(m.engineVerdict());
        points.add(m.atThreshold("configured passive.threshold", configuredThreshold));
        for (double target : new double[] {0.10, 0.05, 0.01}) {
            PadMetrics.OperatingPoint op = m.bpcerAtApcer(target);
            if (op != null) {
                points.add(op);
            }
        }

        r.append("## Operating points\n\n");
        r.append("| Operating point | Threshold | Worst-case APCER (species) | BPCER |\n|---|---|---|---|\n");
        for (PadMetrics.OperatingPoint op : points) {
            PadMetrics.Rate worst = op.apcerWorst();
            r.append("| ").append(op.name).append(" | ")
                    .append(Double.isNaN(op.threshold) ? "—" : String.format(Locale.ROOT, "%.1f", op.threshold)).append(" | ")
                    .append(worst == null ? "n/a" : pct(worst) + " (" + op.worstSpecies() + ")").append(" | ")
                    .append(pct(op.bpcer)).append(" |\n");
        }
        PadMetrics.DetPoint eer = m.equalErrorPoint();
        if (eer != null) {
            r.append(String.format(Locale.ROOT, "%nEqual-error point: threshold %.1f, worst-case APCER %.2f%%, BPCER %.2f%%.%n",
                    eer.threshold, 100 * eer.apcerWorst, 100 * eer.bpcer));
        }
        r.append("\nHow to read this for the Registration Client: the **engine PAD verdict** row is attacks not\n"
                + "blocked by PAD. The **configured passive.threshold** row is attacks that would also pass the\n"
                + "passive check and so skip active challenges, and BPCER there is live people sent to a challenge.\n"
                + "Active challenges then stop most of the remaining replay and photo attacks; that system-level\n"
                + "rate is measured by the session simulation tests, not by this report.\n\n");

        if (!m.species().isEmpty()) {
            r.append("## APCER per PAI species\n\n| Species | Attacks |");
            for (PadMetrics.OperatingPoint op : points) {
                r.append(' ').append(op.name).append(" |");
            }
            r.append("\n|---|---|");
            for (int i = 0; i < points.size(); i++) {
                r.append("---|");
            }
            r.append('\n');
            for (String s : m.species()) {
                r.append("| ").append(s).append(" | ").append(points.get(0).apcerBySpecies.get(s).total).append(" |");
                for (PadMetrics.OperatingPoint op : points) {
                    r.append(' ').append(pct(op.apcerBySpecies.get(s))).append(" |");
                }
                r.append('\n');
            }
            r.append('\n');
        }

        r.append("## Non-response (no face, several faces, unreadable, engine error)\n\n| Population | Rate |\n|---|---|\n");
        r.append("| bona fide | ").append(pct(m.nonResponse(null))).append(" |\n");
        for (String s : m.species()) {
            r.append("| ").append(s).append(" | ").append(pct(m.nonResponse(s))).append(" |\n");
        }

        List<Double> perFrame = new ArrayList<>();
        int frames = 0;
        for (ScoredPresentation p : m.presentations()) {
            frames += p.getFrames();
            if (p.getFrames() > 0) {
                perFrame.add(p.getMsPerFrame());
            }
        }
        Collections.sort(perFrame);
        if (!perFrame.isEmpty()) {
            r.append(String.format(Locale.ROOT, "%n## Processing time%n%n%d frames analysed; per-presentation mean "
                            + "analysis time: median %.1f ms/frame, 95th percentile %.1f ms/frame.%n",
                    frames, perFrame.get(perFrame.size() / 2),
                    perFrame.get(Math.min(perFrame.size() - 1, (int) Math.ceil(0.95 * perFrame.size()) - 1))));
        }

        PadMetrics.OperatingPoint bpcer20 = m.bpcerAtApcer(0.05);
        r.append("\n## Calibration\n\n");
        if (bpcer20 != null) {
            r.append(String.format(Locale.ROOT, "To keep worst-case APCER at or below 5%% on this dataset, set "
                    + "`passive.threshold=%d` (BPCER %s). Re-run on data from the target devices before changing "
                    + "production configuration.%n", (int) Math.ceil(bpcer20.threshold), pct(bpcer20.bpcer)));
        } else {
            r.append("No threshold reaches 5% worst-case APCER on this dataset (or it has no attacks); rely on the\n"
                    + "engine PAD verdict and active challenges, and improve the PAD model.\n");
        }
        return r.toString();
    }

    private static String pct(PadMetrics.Rate rate) {
        if (rate == null || rate.total == 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2f%% [%.2f–%.2f] (%d/%d)", 100 * rate.value(), 100 * rate.lower(),
                100 * rate.upper(), rate.errors, rate.total);
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static List<Path> media(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).filter(PadEvaluation::isMedia).sorted().collect(Collectors.toList());
        }
    }

    static boolean isMedia(Path f) {
        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return IMAGE_EXTENSIONS.contains(ext) || VIDEO_EXTENSIONS.contains(ext);
    }
}
