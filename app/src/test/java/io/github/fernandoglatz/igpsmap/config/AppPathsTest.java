package io.github.fernandoglatz.igpsmap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {

    @TempDir
    Path dir;

    private Path repo() throws IOException {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve("app"));
        Files.writeString(repo.resolve("maps.csv"), "h\n");
        Files.writeString(repo.resolve("tag-igpsport.xml"), "<tag-mapping/>\n");
        return repo;
    }

    @Test
    void usesRepoRootWhenStartedThere() throws IOException {
        Path repo = repo();
        AppPaths paths = AppPaths.detect(repo, "Linux", dir.resolve("home"), Map.of());
        assertEquals(repo, paths.root());
        assertEquals(repo.resolve("download"), paths.download());
        assertEquals(repo.resolve("tmp"), paths.tmp());
        assertEquals(repo.resolve("cache"), paths.cache());
        assertEquals(repo.resolve("output"), paths.output());
        assertEquals(repo.resolve("maps.csv"), paths.mapsCsv());
    }

    @Test
    void usesRepoRootWhenStartedFromAppFolder() throws IOException {
        Path repo = repo();
        assertEquals(repo, AppPaths.detect(repo.resolve("app"), "Linux", dir.resolve("home"), Map.of()).root());
    }

    @Test
    void fallsBackToUserDataDirectoryPerOs() {
        Path home = dir.resolve("home");
        Path elsewhere = dir.resolve("elsewhere");
        assertEquals(home.resolve(".local/share/igpsport-map-updater"),
                AppPaths.detect(elsewhere, "Linux", home, Map.of()).root());
        assertEquals(home.resolve("Library/Application Support/igpsport-map-updater"),
                AppPaths.detect(elsewhere, "Mac OS X", home, Map.of()).root());
        assertEquals(Path.of("L:/appdata").resolve("igpsport-map-updater"),
                AppPaths.detect(elsewhere, "Windows 11", home, Map.of("LOCALAPPDATA", "L:/appdata")).root());
    }

    @Test
    void settingsFileLivesInUserConfigDirectory() {
        Path home = dir.resolve("home");
        assertEquals(home.resolve(".config/igpsport-map-updater/settings.properties"),
                AppPaths.detect(dir, "Linux", home, Map.of()).settingsFile());
        assertEquals(Path.of("/xdg/igpsport-map-updater/settings.properties"),
                AppPaths.detect(dir, "Linux", home, Map.of("XDG_CONFIG_HOME", "/xdg")).settingsFile());
    }
}
