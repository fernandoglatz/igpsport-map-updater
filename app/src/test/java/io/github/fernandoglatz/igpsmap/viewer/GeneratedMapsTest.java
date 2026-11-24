package io.github.fernandoglatz.igpsmap.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedMapsTest {

    private static final String ORIGINAL = "BR01002303102B83FO00N00E.map";

    @TempDir
    Path dir;

    private DetectedMap original() throws IOException {
        Path device = Files.createDirectories(dir.resolve("device"));
        Path file = Files.createFile(device.resolve(ORIGINAL));
        return new DetectedMap(file, MapFileName.parse(ORIGINAL).orElseThrow());
    }

    private Path output(String... names) throws IOException {
        Path out = Files.createDirectories(dir.resolve("output"));
        for (String name : names) {
            Files.createFile(out.resolve(name));
        }
        return out;
    }

    @Test
    void findsTheGeneratedMapOfTheSameRegionEvenWhenItsGeocodeChanged() throws IOException {
        // The generated map's bounding box differs slightly, so its GEOCODE is not the original's.
        Path out = output("BR01002601122B83FO00O00F.map");

        Optional<Path> found = GeneratedMaps.find(original(), out);

        assertEquals(Optional.of(out.resolve("BR01002601122B83FO00O00F.map")), found);
    }

    @Test
    void picksTheNewestWhenSeveralRunsLeftFiles() throws IOException {
        Path out = output("BR01002501012B83FO00N00E.map", "BR01002601122B83FO00N00E.map", "BR01002506302B83FO00N00E.map");

        assertEquals(Optional.of(out.resolve("BR01002601122B83FO00N00E.map")), GeneratedMaps.find(original(), out));
    }

    @Test
    void ignoresOtherRegionsAndCountries() throws IOException {
        Path out = output(
                "BR02002601122B83FO00N00E.map", // other region code
                "AR01002601122B83FO00N00E.map", // other country
                "notes.txt",
                "BR0100260112.map"); // not an IGPSPORT name

        assertEquals(Optional.empty(), GeneratedMaps.find(original(), out));
    }

    @Test
    void ignoresASameCodeMapThatDoesNotOverlapTheOriginal() throws IOException {
        // Same country and region code, but tiles far away from the original's area.
        Path out = output("BR0100260112000000001001.map");

        assertEquals(Optional.empty(), GeneratedMaps.find(original(), out));
    }

    @Test
    void neverReturnsTheOriginalItself() throws IOException {
        DetectedMap original = original();

        assertEquals(Optional.empty(), GeneratedMaps.find(original, original.path().getParent()));
    }

    @Test
    void missingOutputFolderMeansNothingGeneratedYet() throws IOException {
        DetectedMap original = original();

        assertEquals(Optional.empty(), GeneratedMaps.find(original, dir.resolve("does-not-exist")));
        assertEquals(Optional.empty(), GeneratedMaps.find(original, null));
    }

    @Test
    void anOutputPathThatIsAFileIsNotAFolder() throws IOException {
        Path file = Files.writeString(dir.resolve("file.txt"), "x");

        assertTrue(GeneratedMaps.find(original(), file).isEmpty());
    }
}
