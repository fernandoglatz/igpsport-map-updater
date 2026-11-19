package io.github.fernandoglatz.igpsmap.catalog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * IGPSPORT products with Mapsforge maps, and the official map catalog each one uses (the
 * {@code mapVersionId} of the support site's map download page). BiNavi Ultra is absent on purpose:
 * its packages hold another map format.
 */
public enum Product {
    IGS630("iGS630", Catalogs.V1),
    BSC300("BSC300", Catalogs.V1),
    BSC300T("BSC300T", Catalogs.V1),
    IGS630S("iGS630S", Catalogs.V2),
    IGS800("iGS800", Catalogs.V3),
    BSC500("BSC500", Catalogs.V3),
    BINAVI("BiNavi", Catalogs.V3),
    BINAVI_AIR("BiNavi Air", Catalogs.V3);

    private static final class Catalogs {
        static final String V1 = "1f504832-9c67-11ef-a0af-000c29d603cc";
        static final String V2 = "20a0bbc3-9c67-11ef-a0af-000c29d603cc";
        static final String V3 = "a959751a-21b5-44a3-b713-bc12ebb208ef";
    }

    private final String displayName;
    private final String catalogId;

    Product(String displayName, String catalogId) {
        this.displayName = displayName;
        this.catalogId = catalogId;
    }

    public String displayName() {
        return displayName;
    }

    public String catalogId() {
        return catalogId;
    }

    /** Upper-case letters and digits of the name, e.g. "BINAVIAIR". */
    public String key() {
        return normalize(displayName);
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * The product whose name appears in {@code label} (e.g. a drive name like "IGS630S"), preferring the
     * longest name so "IGS630S" is not taken for "IGS630".
     */
    public static Optional<Product> detect(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String text = normalize(label);
        return Arrays.stream(values())
                .filter(p -> text.contains(p.key()))
                .max(Comparator.comparingInt(p -> p.key().length()));
    }

    /** The product with this enum name (as stored in settings). */
    public static Optional<Product> fromName(String name) {
        return Arrays.stream(values()).filter(p -> p.name().equals(name)).findFirst();
    }

    private static String normalize(String text) {
        return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
