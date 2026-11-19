package io.github.fernandoglatz.igpsmap.update;

import java.util.Locale;
import java.util.Optional;

/** Operating system and architecture, named like the release files ("linux", "x64"). */
public record Platform(String os, String arch) {

    public static Platform current() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static Platform of(String osName, String osArch) {
        String name = osName.toLowerCase(Locale.ROOT);
        String os = name.startsWith("windows") ? "windows" : name.startsWith("mac") ? "macos" : "linux";
        String a = osArch.toLowerCase(Locale.ROOT);
        String arch = a.equals("aarch64") || a.equals("arm64") ? "arm64" : "x64";
        return new Platform(os, arch);
    }

    /** End of the release file name for this platform and package type, e.g. "-linux-x64.deb". */
    public Optional<String> assetSuffix(PackageType type) {
        String extension = switch (type) {
            case MSI -> "msi";
            case DEB -> "deb";
            case DMG -> "dmg";
            case APP_IMAGE -> os.equals("windows") ? "zip" : "tar.gz";
            case OTHER, SOURCE -> null;
        };
        return extension == null ? Optional.empty() : Optional.of("-" + os + "-" + arch + "." + extension);
    }
}
