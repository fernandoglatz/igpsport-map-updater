package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fernandoglatz.igpsmap.update.Version;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AppInfoTest {

    @Test
    void windowTitleShowsTheVersion() {
        assertEquals("IGPSPORT Map Updater 1.2.0", AppInfo.windowTitle(Optional.of(new Version(1, 2, 0))));
        assertEquals("IGPSPORT Map Updater", AppInfo.windowTitle(Optional.empty()));
    }

    @Test
    void footerShowsTheVersionOrThatItRunsFromSource() {
        assertEquals("Version 1.2.0", AppInfo.versionLabel(Optional.of(new Version(1, 2, 0))));
        assertEquals("Development build", AppInfo.versionLabel(Optional.empty()));
    }
}
