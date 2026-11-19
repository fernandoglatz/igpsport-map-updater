package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.catalog.CatalogNode;
import io.github.fernandoglatz.igpsmap.catalog.Product;
import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** UI-independent rules of the "Add maps" dialog. */
final class CatalogSelection {

    private CatalogSelection() {
    }

    /** Whether a map with the entry's code (e.g. BR0100, FR94) is already in the review list. */
    static boolean inList(CatalogNode node, Collection<DetectedMap> maps) {
        Optional<String> prefix = node.codePrefix();
        return prefix.isPresent() && maps.stream()
                .anyMatch(m -> (m.name().countryCode() + m.name().regionCode()).startsWith(prefix.get()));
    }

    /** Case- and accent-insensitive search over the entry's path ("south-america Brazil São Paulo"). */
    static boolean matches(CatalogNode node, String filter) {
        String wanted = fold(filter == null ? "" : filter).strip();
        return wanted.isEmpty() || fold(String.join(" ", node.path())).contains(wanted);
    }

    /** The product named in one of the device labels, else the last product chosen. */
    static Optional<Product> preselect(List<String> deviceLabels, String lastProduct) {
        for (String label : deviceLabels) {
            Optional<Product> product = Product.detect(label);
            if (product.isPresent()) {
                return product;
            }
        }
        return Product.fromName(lastProduct);
    }

    static String size(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1_000_000) {
            return Math.max(1, Math.round(bytes / 1000.0)) + " KB";
        }
        if (bytes < 1_000_000_000) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1e6);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1e9);
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
