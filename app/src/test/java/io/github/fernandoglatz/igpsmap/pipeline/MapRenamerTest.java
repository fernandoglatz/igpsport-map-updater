package io.github.fernandoglatz.igpsmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.io.InvalidMapFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapRenamerTest {

    @TempDir
    Path dir;

    private static final MapFileName ORIGINAL = MapFileName.parse("BR01002303102B83FO00N00E.map").orElseThrow();

    @Test
    void renamesUsingHeaderBoundingBoxAndPbfDate() throws IOException {
        Path generated = Files.write(dir.resolve("out_1.map"), MapFixtures.distritoFederalMap());
        Path result = MapRenamer.rename(generated, ORIGINAL, LocalDate.of(2026, 1, 12), dir.resolve("output"));
        assertEquals(dir.resolve("output/BR01002601122B83FO00N00E.map"), result);
        assertTrue(Files.exists(result));
        assertFalse(Files.exists(generated));
    }

    @Test
    void replacesExistingTarget() throws IOException {
        Files.createDirectories(dir.resolve("output"));
        Files.writeString(dir.resolve("output/BR01002601122B83FO00N00E.map"), "old");
        Path generated = Files.write(dir.resolve("out_1.map"), MapFixtures.distritoFederalMap());
        Path result = MapRenamer.rename(generated, ORIGINAL, LocalDate.of(2026, 1, 12), dir.resolve("output"));
        assertEquals(80, Files.size(result));
    }

    @Test
    void invalidMapLeavesSourceInPlace() throws IOException {
        Path generated = Files.writeString(dir.resolve("out_1.map"), "garbage");
        assertThrows(InvalidMapFileException.class,
                () -> MapRenamer.rename(generated, ORIGINAL, LocalDate.of(2026, 1, 12), dir.resolve("output")));
        assertTrue(Files.exists(generated));
    }
}
