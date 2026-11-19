package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fernandoglatz.igpsmap.update.PackageType;
import org.junit.jupiter.api.Test;

class UpdateDialogTest {

    @Test
    void describesWhatHappensAfterTheDownloadForEachInstallType() {
        assertEquals(" and starts the installer.", UpdateDialog.afterDownload(PackageType.MSI));
        assertEquals(" and opens it so you can drag the app to Applications.", UpdateDialog.afterDownload(PackageType.DMG));
        assertEquals(" and installs it (your password is needed).", UpdateDialog.afterDownload(PackageType.DEB));
        assertEquals(", installs it and restarts the app.", UpdateDialog.afterDownload(PackageType.APP_IMAGE));
    }
}
