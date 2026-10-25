package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.tools.DatasetLayout.Presentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetLayoutTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 25, 23, 15, 0);

    @TempDir
    Path root;

    @Test
    void clipsGoWhereTheEvaluatorLooks() {
        DatasetLayout layout = new DatasetLayout(root);
        assertEquals(root.resolve("bona_fide/S01/S01_bona_fide_20261025-231500.avi"),
                layout.newClipPath(Presentation.BONA_FIDE, "S01", T));
        assertEquals(root.resolve("attack/printed_photo/S01/S01_printed_photo_20261025-231500.avi"),
                layout.newClipPath(Presentation.PRINTED_PHOTO, "S01", T));
        assertEquals(root.resolve("attack/video_replay/S02/S02_video_replay_20261025-231500.avi"),
                layout.newClipPath(Presentation.VIDEO_REPLAY, "S02", T));
        assertFalse(layout.incompleteDir().startsWith(root.resolve("bona_fide")));
        assertFalse(layout.incompleteDir().startsWith(root.resolve("attack")));
    }

    @Test
    void clipsInTheSameSecondDoNotOverwrite() throws Exception {
        DatasetLayout layout = new DatasetLayout(root);
        Path first = layout.newClipPath(Presentation.BONA_FIDE, "S01", T);
        Files.createDirectories(first.getParent());
        Files.write(first, new byte[] {1});
        Path second = layout.newClipPath(Presentation.BONA_FIDE, "S01", T);
        assertNotEquals(first, second);
        assertTrue(second.getFileName().toString().endsWith("-2.avi"));
    }

    @Test
    void subjectIdsArePseudonymousAndPathSafe() {
        assertTrue(DatasetLayout.isValidSubjectId("S01"));
        assertTrue(DatasetLayout.isValidSubjectId("team-a_07"));
        for (String bad : new String[] {"", "Jane Doe", "../S01", "S01/x", "a\\b", "x".repeat(33), null}) {
            assertFalse(DatasetLayout.isValidSubjectId(bad), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetLayout(root).newClipPath(Presentation.BONA_FIDE, "../escape", T));
    }

    @Test
    void countsOnlyFinishedClipsPerPresentation() throws Exception {
        DatasetLayout layout = new DatasetLayout(root);
        for (String sub : new String[] {"bona_fide/S01/a.avi", "bona_fide/S02/b.avi", "attack/printed_photo/S01/c.avi",
                ".incomplete/d.avi", "bona_fide/S01/notes.txt"}) {
            Path p = root.resolve(sub);
            Files.createDirectories(p.getParent());
            Files.write(p, new byte[] {1});
        }
        assertEquals(2, layout.count(Presentation.BONA_FIDE));
        assertEquals(1, layout.count(Presentation.PRINTED_PHOTO));
        assertEquals(0, layout.count(Presentation.SCREEN_DISPLAY));
    }

    @Test
    void manifestHasAHeaderAndOneRowPerClip() throws Exception {
        DatasetLayout layout = new DatasetLayout(root);
        Path clip = layout.newClipPath(Presentation.SCREEN_DISPLAY, "S03", T);
        layout.log(clip, "S03", Presentation.SCREEN_DISPLAY, 75, 640, 480, 15, 5.0, "webcam:0", T);
        layout.log(clip, "S03", Presentation.SCREEN_DISPLAY, 74, 640, 480, 15, 5.0, "webcam:0", T);
        List<String> lines = Files.readAllLines(root.resolve(DatasetLayout.MANIFEST), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("file,subject,presentation"));
        assertEquals("attack/screen_display/S03/S03_screen_display_20261025-231500.avi,S03,screen_display,75,640,480,"
                + "15.0,5.0,webcam:0,2026-10-25T23:15,true", lines.get(1));
    }
}
