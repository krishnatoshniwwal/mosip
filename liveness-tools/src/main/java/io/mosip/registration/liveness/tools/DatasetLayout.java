package io.mosip.registration.liveness.tools;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where recorded clips go: the folder layout {@code evaluate} reads (see
 * {@link io.mosip.registration.liveness.evaluation.PadEvaluation}), one sub-folder per subject, and a
 * {@value #MANIFEST} log with one row per clip. Subjects are pseudonymous IDs, never names.
 */
final class DatasetLayout {

    enum Presentation {
        BONA_FIDE("bona_fide", "Live person",
                "The subject sits in front of the camera and looks at it naturally: small head movements, normal "
                        + "blinking and expression. Vary distance, lighting and glasses on/off between clips."),
        PRINTED_PHOTO("attack/printed_photo", "Attack: printed photo",
                "Hold a printed photo of the subject's face in front of the camera so it fills the oval. "
                        + "Tilt and move it a little, as an attacker would."),
        SCREEN_DISPLAY("attack/screen_display", "Attack: photo on a screen",
                "Show a still photo of the subject on a phone or tablet in front of the camera, filling the oval. "
                        + "Try different screen brightness levels."),
        VIDEO_REPLAY("attack/video_replay", "Attack: video replay",
                "Play a video of the subject (blinking, turning their head) on a phone, tablet or laptop "
                        + "in front of the camera."),
        MASK("attack/mask", "Attack: mask",
                "Someone else wears or holds a printed or 3D mask of the subject's face in front of the camera.");

        final String folder;
        final String label;
        final String instructions;

        Presentation(String folder, String label, String instructions) {
            this.folder = folder;
            this.label = label;
            this.instructions = instructions;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final String MANIFEST = "recordings.csv";
    static final String INCOMPLETE_DIR = ".incomplete";
    private static final Pattern SUBJECT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final String HEADER = "file,subject,presentation,frames,width,height,fps,seconds,device,recorded_at,"
            + "consent_confirmed\n";

    private final Path root;

    DatasetLayout(Path root) {
        this.root = root.toAbsolutePath();
    }

    Path root() {
        return root;
    }

    /** Pseudonymous subject IDs only: letters, digits, '-' and '_', at most 32 characters. */
    static boolean isValidSubjectId(String id) {
        return id != null && SUBJECT_ID.matcher(id).matches();
    }

    /** A new, unused path for a clip, e.g. {@code bona_fide/S01/S01_bona_fide_20261025-231500.avi}. */
    Path newClipPath(Presentation presentation, String subject, LocalDateTime time) {
        if (!isValidSubjectId(subject)) {
            throw new IllegalArgumentException("Invalid subject ID: " + subject);
        }
        Path dir = root.resolve(presentation.folder).resolve(subject);
        String base = subject + "_" + presentation.name().toLowerCase(Locale.ROOT) + "_" + STAMP.format(time);
        Path candidate = dir.resolve(base + ".avi");
        for (int n = 2; Files.exists(candidate); n++) {
            candidate = dir.resolve(base + "-" + n + ".avi");
        }
        return candidate;
    }

    /** Scratch folder for clips being written; {@code evaluate} only reads bona_fide/ and attack/. */
    Path incompleteDir() {
        return root.resolve(INCOMPLETE_DIR);
    }

    /** Number of finished clips of this kind. */
    int count(Presentation presentation) throws IOException {
        Path dir = root.resolve(presentation.folder);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return (int) files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".avi")).count();
        }
    }

    /** Appends one row to {@value #MANIFEST}, creating it with a header first. */
    synchronized void log(Path clip, String subject, Presentation presentation, int frames, int width, int height,
                          double fps, double seconds, String device, LocalDateTime recordedAt) throws IOException {
        Path manifest = root.resolve(MANIFEST);
        boolean fresh = !Files.exists(manifest);
        try (Writer w = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (fresh) {
                w.write(HEADER);
            }
            w.write(String.join(",",
                    csv(root.relativize(clip).toString().replace('\\', '/')), csv(subject),
                    presentation.name().toLowerCase(Locale.ROOT), Integer.toString(frames), Integer.toString(width),
                    Integer.toString(height), String.format(Locale.ROOT, "%.1f", fps),
                    String.format(Locale.ROOT, "%.1f", seconds), csv(device), recordedAt.toString(), "true"));
            w.write("\n");
        }
    }

    private static String csv(String value) {
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
