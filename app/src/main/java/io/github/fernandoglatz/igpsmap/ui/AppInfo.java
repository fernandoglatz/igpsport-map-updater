package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.update.Version;
import java.util.Optional;

/** Name and version shown in the window. */
final class AppInfo {

    static final String NAME = "IGPSPORT Map Updater";

    private AppInfo() {
    }

    /** "IGPSPORT Map Updater 1.2.0", or just the name when run from source. */
    static String windowTitle(Optional<Version> version) {
        return version.map(v -> NAME + " " + v).orElse(NAME);
    }

    /** Footer text: "Version 1.2.0", or "Development build" when run from source. */
    static String versionLabel(Optional<Version> version) {
        return version.map(v -> "Version " + v).orElse("Development build");
    }
}
