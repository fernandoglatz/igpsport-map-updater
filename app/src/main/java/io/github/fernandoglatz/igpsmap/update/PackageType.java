package io.github.fernandoglatz.igpsmap.update;

import java.util.Locale;

/** How the running app was installed, from the marker set at packaging time (-Digpsmap.package=...). */
public enum PackageType {
    MSI, DEB, DMG,
    /** Portable folder from the .zip / .tar.gz release files. */
    APP_IMAGE,
    /** Another installer type (rpm, pkg, exe) that the release workflow doesn't publish. */
    OTHER,
    /** Run from source (no marker). */
    SOURCE;

    public static final String PROPERTY = "igpsmap.package";

    public static PackageType current() {
        return of(System.getProperty(PROPERTY));
    }

    static PackageType of(String marker) {
        if (marker == null || marker.isBlank()) {
            return SOURCE;
        }
        return switch (marker.strip().toLowerCase(Locale.ROOT)) {
            case "msi" -> MSI;
            case "deb" -> DEB;
            case "dmg" -> DMG;
            case "app-image" -> APP_IMAGE;
            default -> OTHER;
        };
    }
}
