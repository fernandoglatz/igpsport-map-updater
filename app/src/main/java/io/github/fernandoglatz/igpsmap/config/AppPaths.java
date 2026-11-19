package io.github.fernandoglatz.igpsmap.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Working folders: the repository root when run from source, the user data folder when installed. */
public record AppPaths(Path root, Path settingsFile) {

    private static final String APP_DIR = "igpsport-map-updater";

    public static AppPaths detect(Path cwd) {
        return detect(cwd, System.getProperty("os.name", ""), Path.of(System.getProperty("user.home")), System.getenv());
    }

    public static AppPaths detect(Path cwd, String osName, Path home, Map<String, String> env) {
        String os = osName.toLowerCase(Locale.ROOT);
        return new AppPaths(findRoot(cwd.toAbsolutePath(), os, home, env), settingsFile(os, home, env));
    }

    public Path download() {
        return root.resolve("download");
    }

    public Path tmp() {
        return root.resolve("tmp");
    }

    public Path cache() {
        return root.resolve("cache");
    }

    public Path output() {
        return root.resolve("output");
    }

    /** Original maps downloaded from the official iGPSPORT catalog, one folder per catalog. */
    public Path originals() {
        return root.resolve("originals");
    }

    public Path mapsCsv() {
        return root.resolve("maps.csv");
    }

    private static Path findRoot(Path cwd, String os, Path home, Map<String, String> env) {
        for (Path candidate = cwd; candidate != null && candidate.getNameCount() >= cwd.getNameCount() - 1;
             candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("maps.csv")) && Files.isRegularFile(candidate.resolve("tag-igpsport.xml"))) {
                return candidate;
            }
        }
        if (os.startsWith("windows")) {
            String local = env.get("LOCALAPPDATA");
            return (local != null ? Path.of(local) : home.resolve("AppData/Local")).resolve(APP_DIR);
        }
        if (os.startsWith("mac")) {
            return home.resolve("Library/Application Support").resolve(APP_DIR);
        }
        String xdgData = env.get("XDG_DATA_HOME");
        return (xdgData != null ? Path.of(xdgData) : home.resolve(".local/share")).resolve(APP_DIR);
    }

    private static Path settingsFile(String os, Path home, Map<String, String> env) {
        Path base;
        if (os.startsWith("windows")) {
            String appData = env.get("APPDATA");
            base = appData != null ? Path.of(appData) : home.resolve("AppData/Roaming");
        } else if (os.startsWith("mac")) {
            base = home.resolve("Library/Application Support");
        } else {
            String xdg = env.get("XDG_CONFIG_HOME");
            base = xdg != null ? Path.of(xdg) : home.resolve(".config");
        }
        return base.resolve(APP_DIR).resolve("settings.properties");
    }
}
