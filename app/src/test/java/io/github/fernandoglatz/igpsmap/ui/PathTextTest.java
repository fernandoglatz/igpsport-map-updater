package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PathTextTest {

    private static final Path HOME = Path.of("/home/rider");

    @Test
    void abbreviatesTheHomeFolder() {
        assertEquals("~" + java.io.File.separator + "maps" + java.io.File.separator + "output",
                PathText.display(Path.of("/home/rider/maps/output"), HOME));
        assertEquals("~", PathText.display(HOME, HOME));
    }

    @Test
    void leavesOtherPathsAlone() {
        // Absolute form, so the expectation also holds on Windows (where it gains a drive letter).
        assertEquals(Path.of("/media/usb/output").toAbsolutePath().toString(),
                PathText.display(Path.of("/media/usb/output"), HOME));
        assertEquals(Path.of("/home/riderX/out").toAbsolutePath().toString(),
                PathText.display(Path.of("/home/riderX/out"), HOME));
    }

    @Test
    void expandsATypedTilde() {
        assertEquals(HOME.resolve("maps"), PathText.parse("~/maps", HOME));
        assertEquals(HOME, PathText.parse(" ~ ", HOME));
        assertEquals(Path.of("/srv/out"), PathText.parse("/srv/out", HOME));
    }
}
