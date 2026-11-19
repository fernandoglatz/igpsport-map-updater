package io.github.fernandoglatz.igpsmap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals(4, s.threads());
        assertEquals(8, s.maxHeapGb());
        assertEquals(Duration.ofDays(7), s.cacheMaxAge());
        assertNull(s.lastSourceFolder());
    }

    @Test
    void roundTrips() throws IOException {
        Path file = dir.resolve("cfg/settings.properties");
        Settings s = new Settings(dir.resolve("out"), 6, 12, Duration.ofDays(3), dir.resolve("backup"));
        s.save(file);
        assertEquals(s, Settings.load(file, paths()));
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
        assertEquals(4, s.threads());
        assertEquals(8, s.maxHeapGb());
        assertEquals(Duration.ofDays(2), s.cacheMaxAge());
    }
}
