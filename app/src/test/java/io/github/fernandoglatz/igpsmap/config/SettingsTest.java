package io.github.fernandoglatz.igpsmap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

    @TempDir
    Path dir;

    private AppPaths paths() {
        return AppPaths.detect(dir, "Linux", dir.resolve("home"), Map.of());
    }

    @Test
    void defaults() {
        Settings s = Settings.defaults(paths());
        assertEquals(paths().output(), s.outputDir());
        assertEquals(Settings.AUTO, s.threads());
        assertEquals(Settings.AUTO, s.maxHeapGb());
        assertEquals(Duration.ofDays(7), s.cacheMaxAge());
        assertNull(s.lastSourceFolder());
        assertTrue(s.hdFallback());
        assertNull(s.lastProduct());
        assertTrue(s.igs630Compatibility());
        assertTrue(s.generateMd5());
    }

    @Test
    void generationOptionsRoundTripAndSurviveOtherChanges() throws IOException {
        Settings s = Settings.defaults(paths()).withGenerationOptions(false, true)
                .withLastSourceFolder(dir).withLastProduct("IGS630").withSkippedVersion("1.2.1");
        Path file = dir.resolve("options.properties");
        s.save(file);
        Settings loaded = Settings.load(file, paths());
        assertEquals(s, loaded);
        assertFalse(loaded.igs630Compatibility());
        assertTrue(loaded.generateMd5());
    }

    @Test
    void roundTrips() throws IOException {
        Path file = dir.resolve("cfg/settings.properties");
        Settings s = new Settings(dir.resolve("out"), 6, 12, Duration.ofDays(3), dir.resolve("backup"), true, "IGS630S");
        s.save(file);
        assertEquals(s, Settings.load(file, paths()));
    }

    @Test
    void roundTripsAutoValues() throws IOException {
        Path file = dir.resolve("cfg/auto.properties");
        Settings s = new Settings(dir.resolve("out"), Settings.AUTO, Settings.AUTO, Duration.ofDays(7), null, false, null);
        s.save(file);
        assertTrue(Files.readString(file).contains("threads=auto"));
        assertTrue(Files.readString(file).contains("maxHeapGb=auto"));
        assertEquals(s, Settings.load(file, paths()));
    }

    @Test
    void v1SettingsFileLoadsUnchanged() throws IOException {
        Path file = dir.resolve("v1.properties");
        Files.writeString(file, "outputDir=" + dir.resolve("out").toString().replace("\\", "\\\\")
                + "\nthreads=6\nmaxHeapGb=12\ncacheMaxAgeDays=7\n");
        Settings s = Settings.load(file, paths());
        assertEquals(6, s.threads());
        assertEquals(12, s.maxHeapGb());
        assertTrue(s.hdFallback(), "files saved before the option existed get the default (on)");
        assertNull(s.lastProduct());
        assertTrue(s.igs630Compatibility(), "existing installations migrate to the conservative profile");
        assertTrue(s.generateMd5());
    }

    @Test
    void skippedVersionRoundTrips() throws IOException {
        Path file = dir.resolve("skip.properties");
        Settings s = Settings.defaults(paths()).withSkippedVersion("1.2.0");
        s.save(file);
        assertEquals("1.2.0", Settings.load(file, paths()).skippedVersion());
        assertNull(Settings.defaults(paths()).skippedVersion());
        assertEquals(s.withSkippedVersion(null), Settings.defaults(paths()));
    }

    @Test
    void disabledRetryIsKept() throws IOException {
        Path file = dir.resolve("off.properties");
        Settings s = new Settings(dir.resolve("out"), Settings.AUTO, Settings.AUTO, Duration.ofDays(7), null, false, null);
        s.save(file);
        assertFalse(Settings.load(file, paths()).hdFallback());
    }

    @Test
    void autoKeywordIsCaseInsensitive() throws IOException {
        Path file = dir.resolve("a.properties");
        Files.writeString(file, "threads=AUTO\nmaxHeapGb= Auto \nhdFallback=TRUE\nlastProduct=BINAVI\n");
        Settings s = Settings.load(file, paths());
        assertEquals(Settings.AUTO, s.threads());
        assertEquals(Settings.AUTO, s.maxHeapGb());
        assertTrue(s.hdFallback());
        assertEquals("BINAVI", s.lastProduct());
    }

    @Test
    void withersKeepTheOtherFields() {
        Settings s = new Settings(dir, 3, 5, Duration.ofDays(1), null, true, "IGS800");
        assertEquals(new Settings(dir, 3, 5, Duration.ofDays(1), dir, true, "IGS800"), s.withLastSourceFolder(dir));
        assertEquals(new Settings(dir, 3, 5, Duration.ofDays(1), null, true, "BSC300"), s.withLastProduct("BSC300"));
    }

    @Test
    void missingOrCorruptFileGivesDefaults() throws IOException {
        assertEquals(Settings.defaults(paths()), Settings.load(dir.resolve("none.properties"), paths()));
        Path bad = dir.resolve("bad.properties");
        Files.write(bad, new byte[] {(byte) 0xFF, '\\', 'u', 'Z', 'Z'});
        assertEquals(Settings.defaults(paths()), Settings.load(bad, paths()));
    }

    @Test
    void invalidPathsFallBackToDefaults() throws IOException {
        Path file = dir.resolve("p.properties");
        Files.writeString(file, "outputDir=bad\\u0000path\nlastSourceFolder=x\\u0000y\nthreads=3\n");
        Settings s = Settings.load(file, paths());
        assertEquals(paths().output(), s.outputDir());
        assertNull(s.lastSourceFolder());
        assertEquals(3, s.threads());
    }

    @Test
    void invalidNumbersFallBackPerField() throws IOException {
        Path file = dir.resolve("s.properties");
        Files.writeString(file, "threads=abc\nmaxHeapGb=0\ncacheMaxAgeDays=2\n");
        Settings s = Settings.load(file, paths());
        assertEquals(Settings.AUTO, s.threads());
        assertEquals(Settings.AUTO, s.maxHeapGb());
        assertEquals(Duration.ofDays(2), s.cacheMaxAge());
    }
}
