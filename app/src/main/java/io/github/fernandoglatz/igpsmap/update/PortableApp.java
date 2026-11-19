package io.github.fernandoglatz.igpsmap.update;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/** The folder of a portable installation and its launcher (from the jpackage.app-path property). */
public record PortableApp(Path root, Path launcher, Platform platform) {

    /** System property the jpackage launcher sets to its own path. */
    public static final String APP_PATH_PROPERTY = "jpackage.app-path";

    public static Optional<PortableApp> current() {
        return locate(System.getProperty(APP_PATH_PROPERTY), Platform.current());
    }

    /**
     * The app folder for a launcher at {@code appPath}: {@code <root>/bin/<name>} on Linux,
     * {@code <root>\<name>.exe} on Windows and {@code <root>.app/Contents/MacOS/<name>} on macOS.
     * Empty for any other layout, so nothing is replaced by guesswork.
     */
    static Optional<PortableApp> locate(String appPath, Platform platform) {
        if (appPath == null || appPath.isBlank()) {
            return Optional.empty();
        }
        Path launcher;
        try {
            launcher = Path.of(appPath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        Path root = switch (platform.os()) {
            case "windows" -> launcher.getParent();
            case "macos" -> named(launcher.getParent(), "MacOS") && named(parent(launcher, 2), "Contents")
                    ? parent(launcher, 3) : null;
            default -> named(launcher.getParent(), "bin") ? parent(launcher, 2) : null;
        };
        return root == null || root.getParent() == null ? Optional.empty()
                : Optional.of(new PortableApp(root, launcher, platform));
    }

    private static Path parent(Path path, int levels) {
        Path p = path;
        for (int i = 0; i < levels && p != null; i++) {
            p = p.getParent();
        }
        return p;
    }

    private static boolean named(Path path, String name) {
        return path != null && path.getFileName() != null && path.getFileName().toString().equals(name);
    }
}
