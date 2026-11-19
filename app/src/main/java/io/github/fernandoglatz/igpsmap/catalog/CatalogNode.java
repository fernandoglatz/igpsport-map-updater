package io.github.fernandoglatz.igpsmap.catalog;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One entry of the official map catalog: a continent, country or region. {@code path} holds the names
 * from the top level down to this node; {@code url} is null for entries that only group others.
 * {@code size} is the package size in bytes as listed by the catalog.
 */
public record CatalogNode(String id, String name, List<String> path, long size, String version, URI url,
                          List<CatalogNode> children) {

    private static final Pattern CODE = Pattern.compile("^([A-Z]{2}\\d{2,4})", Pattern.CASE_INSENSITIVE);

    public CatalogNode {
        path = List.copyOf(path);
        children = List.copyOf(children);
    }

    public boolean downloadable() {
        return url != null;
    }

    /** Map code from the link's file name, e.g. "BR0100" for ".../BR0100.zip" or "FR94" for ".../FR94.zip". */
    public Optional<String> codePrefix() {
        if (url == null || url.getPath() == null) {
            return Optional.empty();
        }
        String path = url.getPath();
        Matcher m = CODE.matcher(path.substring(path.lastIndexOf('/') + 1));
        return m.find() ? Optional.of(m.group(1).toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    /** Every downloadable node below (and including) {@code nodes}, depth first. */
    public static List<CatalogNode> leaves(List<CatalogNode> nodes) {
        List<CatalogNode> result = new ArrayList<>();
        for (CatalogNode node : nodes) {
            if (node.downloadable()) {
                result.add(node);
            }
            result.addAll(leaves(node.children()));
        }
        return result;
    }
}
