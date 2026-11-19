package io.github.fernandoglatz.igpsmap.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void parsesTagsAndPlainVersions() {
        assertEquals(Optional.of(new Version(1, 2, 3)), Version.parse("v1.2.3"));
        assertEquals(Optional.of(new Version(1, 2, 3)), Version.parse("1.2.3"));
        assertEquals(Optional.of(new Version(1, 2, 0)), Version.parse("V1.2"));
        assertEquals(Optional.of(new Version(2, 0, 0)), Version.parse(" 2 "));
    }

    @Test
    void rejectsAnythingElse() {
        assertTrue(Version.parse("latest").isEmpty());
        assertTrue(Version.parse("1.2.3-beta").isEmpty());
        assertTrue(Version.parse("").isEmpty());
        assertTrue(Version.parse(null).isEmpty());
    }

    @Test
    void comparesNumerically() {
        assertTrue(new Version(1, 10, 0).compareTo(new Version(1, 9, 9)) > 0);
        assertTrue(new Version(1, 1, 0).compareTo(new Version(1, 1, 0)) == 0);
        assertTrue(new Version(0, 9, 0).compareTo(new Version(1, 0, 0)) < 0);
    }

    @Test
    void printsWithoutThePrefix() {
        assertEquals("1.2.3", new Version(1, 2, 3).toString());
    }

    @Test
    void currentVersionComesFromTheOverrideOrTheManifest() {
        assertEquals(Optional.of(new Version(1, 0, 0)), Version.current("1.0.0", null));
        assertEquals(Optional.of(new Version(1, 1, 0)), Version.current(null, "1.1.0"));
        assertEquals(Optional.of(new Version(1, 0, 0)), Version.current("1.0.0", "1.1.0"));
        assertTrue(Version.current(null, null).isEmpty());
        assertTrue(Version.current("dev", null).isEmpty());
    }
}
