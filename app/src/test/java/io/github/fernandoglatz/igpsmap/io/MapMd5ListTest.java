package io.github.fernandoglatz.igpsmap.io;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapMd5ListTest {
    @TempDir Path dir;
    private static final String FIRST = "AT01002611193FV26Y00R019";
    private static final String SECOND = "CZ01002611193EB25J01C012";

    @Test
    void hashesFinalMapsWithReferenceFormatLowercaseAndCrLf() throws IOException {
        Files.writeString(dir.resolve(SECOND + ".map"), "abc", StandardCharsets.US_ASCII);
        Files.write(dir.resolve(FIRST + ".map"), new byte[0]);
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        Files.writeString(dir.resolve("LI00A026082307H04Z000000.map"), "overview is not a main map");
        Path target = MapMd5List.write(dir, List.of(), () -> false);
        assertEquals("MAP_NUM:2\r\nMAP_VER:130\r\n"
                + FIRST + ":d41d8cd98f00b204e9800998ecf8427e\r\n"
                + SECOND + ":900150983cd24fb0d6963f7d28e17f72\r\n",
                Files.readString(target, StandardCharsets.US_ASCII));
    }

    @Test
    void preservesExistingVersionAndReplacesStaleEntries() throws IOException {
        Files.writeString(dir.resolve(SECOND + ".map"), "abc");
        Files.writeString(dir.resolve(MapMd5List.FILE_NAME), "MAP_NUM:432\r\nMAP_VER:137\r\nstale:old\r\n");
        Path reference = Files.writeString(dir.resolve("reference.cfg"), "MAP_VER:999\r\n");
        Path target = MapMd5List.write(dir, List.of(reference), () -> false);
        String text = Files.readString(target);
        assertTrue(text.startsWith("MAP_NUM:1\r\nMAP_VER:137\r\n"));
        assertFalse(text.contains("stale"));
    }

    @Test
    void readsVersionFromOriginalCfgWhenOutputListDoesNotExist() throws IOException {
        Path reference = Files.writeString(dir.resolve("reference.cfg"), "MAP_NUM:432\r\nMAP_VER:135\r\n");
        assertTrue(Files.readString(MapMd5List.write(dir, List.of(reference), () -> false)).contains("MAP_VER:135\r\n"));
    }

    @Test
    void cancellationKeepsExistingCfgIntact() throws IOException {
        Files.writeString(dir.resolve(SECOND + ".map"), "abc");
        Path cfg = Files.writeString(dir.resolve(MapMd5List.FILE_NAME), "MAP_NUM:432\r\nMAP_VER:130\r\nstale:old\r\n");
        byte[] before = Files.readAllBytes(cfg);
        assertThrows(CancellationException.class, () -> MapMd5List.write(dir, List.of(), () -> true));
        assertArrayEquals(before, Files.readAllBytes(cfg));
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".map-md5-")));
        }
    }
}
