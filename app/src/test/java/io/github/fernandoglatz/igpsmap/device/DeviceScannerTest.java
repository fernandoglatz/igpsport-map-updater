package io.github.fernandoglatz.igpsmap.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceScannerTest {

    private static final String DF = "BR01002303102B83FO00N00E.map";
    private static final String GO = "BR02002303102833DN04O04Q.map";

    @TempDir
    Path dir;

    private Path touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "x");
    }

    private DeviceScanner linuxScanner() {
        return new DeviceScanner("Linux", "rider", List::of, dir);
    }

    @Test
    void findsMapsWithinDepthLimitAndIgnoresOtherFiles() throws IOException {
        Path root = dir.resolve("dev");
        touch(root.resolve(DF));
        touch(root.resolve("a/b/" + GO));
        touch(root.resolve("a/b/c/d/e/PT01002303102B83FO00N00E.map"));
        touch(root.resolve("notes.txt"));
        touch(root.resolve("out_1.map"));
        touch(root.resolve(DF + ".bak"));

        List<DetectedMap> maps = linuxScanner().scan(root, 4);

        assertEquals(List.of(DF, GO), maps.stream().map(m -> m.path().getFileName().toString()).toList());
        assertEquals("BR", maps.get(0).name().countryCode());
    }

    @Test
    void skipsBackupFoldersInAnyCase() throws IOException {
        // Device layout seen on an iGS630S: originals in Backup/, regenerated maps in iGPSPORT/Maps/.
        Path root = dir.resolve("dev");
        touch(root.resolve("Backup/" + DF));
        touch(root.resolve("old/backup/" + GO));
        touch(root.resolve("BACKUP/PT01002303102B83FO00N00E.map"));
        touch(root.resolve("iGPSPORT/Maps/AR010023031021O3S204B068.map"));

        assertEquals(List.of("AR010023031021O3S204B068.map"),
                linuxScanner().scan(root, 4).stream().map(m -> m.path().getFileName().toString()).toList());
    }

    @Test
    void aChosenBackupFolderIsStillScanned() throws IOException {
        Path root = dir.resolve("Backup");
        touch(root.resolve(DF));
        assertEquals(1, linuxScanner().scan(root, 4).size());
    }

    @Test
    void keepsOneFilePerRegionPreferringTheNewestDate() throws IOException {
        Path root = dir.resolve("dev");
        touch(root.resolve("old/BR01002303102B83FO00N00E.map"));
        touch(root.resolve("iGPSPORT/Maps/BR01002601202B83FO00N00E.map"));
        touch(root.resolve("iGPSPORT/Maps/AR010023031021O3S204B068.map"));

        List<DetectedMap> maps = linuxScanner().scan(root, 4);

        assertEquals(List.of("AR010023031021O3S204B068.map", "BR01002601202B83FO00N00E.map"),
                maps.stream().map(m -> m.path().getFileName().toString()).toList());
    }

    @Test
    void skipsHiddenAndSystemFolders() throws IOException {
        Path root = dir.resolve("dev");
        touch(root.resolve(".Trashes/" + DF));
        touch(root.resolve("System Volume Information/" + DF));
        touch(root.resolve("map/" + GO));
        assertEquals(1, linuxScanner().scan(root, 4).size());
    }

    @Test
    void skipsUnreadableFolders() throws IOException {
        assumeTrue(File.separatorChar == '/');
        Path root = dir.resolve("dev");
        Path locked = root.resolve("locked");
        touch(locked.resolve(DF));
        touch(root.resolve("ok/" + GO));
        assumeTrue(locked.toFile().setReadable(false));
        try {
            assertEquals(1, linuxScanner().scan(root, 4).size());
        } finally {
            locked.toFile().setReadable(true);
        }
    }

    @Test
    void missingRootScansEmpty() {
        assertTrue(linuxScanner().scan(dir.resolve("gone"), 4).isEmpty());
    }

    @Test
    void linuxMountRootsListMediaAndRunMedia() throws IOException {
        Files.createDirectories(dir.resolve("media/rider/IGS630"));
        Files.createDirectories(dir.resolve("run/media/rider/USB"));
        assertEquals(List.of(dir.resolve("media/rider/IGS630"), dir.resolve("run/media/rider/USB")),
                linuxScanner().mountRoots());
    }

    @Test
    void macMountRootsListVolumes() throws IOException {
        Files.createDirectories(dir.resolve("Volumes/IGS630"));
        assertEquals(List.of(dir.resolve("Volumes/IGS630")),
                new DeviceScanner("Mac OS X", "rider", List::of, dir).mountRoots());
    }

    @Test
    void windowsMountRootsExcludeSystemDrive() {
        Path c = Path.of("C:\\");
        Path e = Path.of("E:\\"); // not under the temp dir: on Windows that lives on the system drive
        DeviceScanner scanner = new DeviceScanner("Windows 11", "rider", () -> List.of(c, e), dir);
        assertEquals(List.of(e), scanner.mountRoots());
    }

    @Test
    void detectDevicesKeepsOnlyRootsWithMaps() throws IOException {
        touch(dir.resolve("media/rider/IGS630/Map/" + DF));
        touch(dir.resolve("media/rider/IGS630/Map/" + GO));
        Files.createDirectories(dir.resolve("media/rider/EMPTY"));

        List<DetectedSource> sources = linuxScanner().detectDevices();

        assertEquals(1, sources.size());
        assertEquals("IGS630 — 2 maps", sources.get(0).label());
        assertEquals(2, sources.get(0).maps().size());
    }
}
