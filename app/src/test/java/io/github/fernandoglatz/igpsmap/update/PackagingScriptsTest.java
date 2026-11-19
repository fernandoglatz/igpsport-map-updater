package io.github.fernandoglatz.igpsmap.update;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** In-app updates depend on two packaging details that must not get lost. */
class PackagingScriptsTest {

    @Test
    void everyInstallerRecordsItsType() throws IOException {
        assertTrue(Files.readString(Path.of("package.sh")).contains("-D" + PackageType.PROPERTY + "=$TYPE"));
        assertTrue(Files.readString(Path.of("package.ps1")).contains("-D" + PackageType.PROPERTY + "=$t"));
    }

    @Test
    void windowsInstallersKeepTheirUpgradeCode() throws IOException {
        // Same upgrade code as the published v1.0.0 and v1.1.0 MSIs, so a new MSI replaces the old install.
        assertTrue(Files.readString(Path.of("package.ps1"))
                .contains("--win-upgrade-uuid\", \"49a86519-a3be-3472-9700-e505b3df2b60\""));
    }
}
