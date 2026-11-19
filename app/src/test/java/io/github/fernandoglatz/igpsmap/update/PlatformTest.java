package io.github.fernandoglatz.igpsmap.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlatformTest {

    @Test
    void namesOsAndArchitectureLikeTheReleaseFiles() {
        assertEquals(new Platform("windows", "x64"), Platform.of("Windows 11", "amd64"));
        assertEquals(new Platform("linux", "x64"), Platform.of("Linux", "x86_64"));
        assertEquals(new Platform("macos", "arm64"), Platform.of("Mac OS X", "aarch64"));
        assertEquals(new Platform("linux", "arm64"), Platform.of("Linux", "arm64"));
    }

    @Test
    void assetSuffixDependsOnThePackageType() {
        assertEquals(Optional.of("-windows-x64.msi"), new Platform("windows", "x64").assetSuffix(PackageType.MSI));
        assertEquals(Optional.of("-linux-x64.deb"), new Platform("linux", "x64").assetSuffix(PackageType.DEB));
        assertEquals(Optional.of("-macos-arm64.dmg"), new Platform("macos", "arm64").assetSuffix(PackageType.DMG));
        assertEquals(Optional.of("-windows-x64.zip"), new Platform("windows", "x64").assetSuffix(PackageType.APP_IMAGE));
        assertEquals(Optional.of("-linux-x64.tar.gz"), new Platform("linux", "x64").assetSuffix(PackageType.APP_IMAGE));
        assertEquals(Optional.of("-macos-arm64.tar.gz"), new Platform("macos", "arm64").assetSuffix(PackageType.APP_IMAGE));
    }

    @Test
    void typesWithoutReleaseFilesHaveNoSuffix() {
        assertTrue(new Platform("linux", "x64").assetSuffix(PackageType.SOURCE).isEmpty());
        assertTrue(new Platform("linux", "x64").assetSuffix(PackageType.OTHER).isEmpty());
    }

    @Test
    void packageTypeComesFromThePackagingMarker() {
        assertEquals(PackageType.MSI, PackageType.of("msi"));
        assertEquals(PackageType.DEB, PackageType.of("deb"));
        assertEquals(PackageType.DMG, PackageType.of("DMG"));
        assertEquals(PackageType.APP_IMAGE, PackageType.of("app-image"));
        assertEquals(PackageType.OTHER, PackageType.of("rpm"));
        assertEquals(PackageType.SOURCE, PackageType.of(null));
        assertEquals(PackageType.SOURCE, PackageType.of(" "));
    }
}
