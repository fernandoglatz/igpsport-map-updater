package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceFoldersTest {

    private static final Path HOME = Path.of("/home/rider");
    private static final String S = File.separator;

    private static List<DetectedMap> maps(Path folder, int count) {
        List<DetectedMap> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = String.format("BR%02d002303102B83FO00N00E.map", i + 1);
            out.add(new DetectedMap(folder.resolve(name), MapFileName.parse(name).orElseThrow()));
        }
        return out;
    }

    @Test
    void showsTheFullFolderThatHoldsTheMaps() {
        Path root = Path.of("/media/rider/IGS630S");
        assertEquals(root.resolve("iGPSPORT/Maps").toAbsolutePath() + " · 3 maps",
                SourceFolders.describe(root, maps(root.resolve("iGPSPORT/Maps"), 3)));
    }

    @Test
    void homeFolderIsNotAbbreviated() {
        Path root = HOME.resolve("backup");
        assertEquals(root.toAbsolutePath() + " · 1 map", SourceFolders.describe(root, maps(root, 1)));
    }

    @Test
    void severalFoldersAreListedLargestFirst() {
        Path root = Path.of("/media/usb");
        List<DetectedMap> all = new ArrayList<>(maps(root.resolve("Other"), 1));
        all.addAll(maps(root.resolve("Maps"), 2));
        assertEquals(root.resolve("Maps").toAbsolutePath() + " (2 maps), " + root.resolve("Other").toAbsolutePath() + " (1 map)",
                SourceFolders.describe(root, all));
    }

    @Test
    void noMaps() {
        Path root = Path.of("/media/usb");
        assertEquals(root.toAbsolutePath() + " · no IGPSPORT maps found", SourceFolders.describe(root, List.of()));
    }
}
