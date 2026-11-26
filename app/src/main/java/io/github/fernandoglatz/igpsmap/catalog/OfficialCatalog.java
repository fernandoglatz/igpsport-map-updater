package io.github.fernandoglatz.igpsmap.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.fernandoglatz.igpsmap.net.DiskCache;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import java.io.IOException;
import java.net.URI;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The official iGPSPORT map catalog of a product, from the public support-site API. */
public final class OfficialCatalog {

    public static final URI DEFAULT_API = URI.create("https://prod.en.igpsport.com/service/web/api/");

    private static final Comparator<CatalogNode> BY_NAME = Comparator.comparing(CatalogNode::name,
            Collator.getInstance(Locale.ROOT));

    private final HttpFetcher fetcher;
    private final DiskCache cache;
    private final URI api;

    public OfficialCatalog(HttpFetcher fetcher, DiskCache cache, URI api) {
        this.fetcher = fetcher;
        this.cache = cache;
        this.api = api;
    }

    /** Top-level nodes (continents) of the product's catalog, cached like the other sources. */
    public List<CatalogNode> load(Product product) throws IOException {
        URI uri = api.resolve("Map/MapInfoList?mapVersionId=" + product.catalogId() + "&language=en");
        String json = cache.getOrFetch(uri.toString(), () -> {
            String text = fetcher.getText(uri);
            parse(text); // never cache an error answer
            return text;
        });
        return parse(json);
    }

    public static List<CatalogNode> parse(String json) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Unexpected answer from the iGPSPORT map catalog", e);
        }
        long code = number(root, "code", -1);
        if (code != 0) {
            String message = text(root, "message");
            if (message.isEmpty()) {
                message = text(root, "msg");
            }
            throw new IOException("iGPSPORT map catalog returned code " + code + (message.isEmpty() ? "" : ": " + message));
        }
        JsonElement data = root.get("data");
        return data != null && data.isJsonArray() ? nodes(data.getAsJsonArray(), List.of()) : List.of();
    }

    private static List<CatalogNode> nodes(JsonArray array, List<String> parentPath) {
        List<CatalogNode> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject o = element.getAsJsonObject();
            String name = text(o, "name");
            List<String> path = new ArrayList<>(parentPath);
            path.add(name);
            JsonElement sub = o.get("subList");
            List<CatalogNode> children = sub != null && sub.isJsonArray() ? nodes(sub.getAsJsonArray(), path) : List.of();
            String link = text(o, "mapPathUrl");
            if (link.isEmpty()) {
                link = text(o, "fileUrl");
            }
            long size = Math.max(0, number(o, "mapSize", 0));
            String version = text(o, "mapVersion");
            URI download = uri(link);
            // V1/V2 advertise nonexistent Slovakia/Brazil aggregates; regions work.
            if (!children.isEmpty() && download != null
                    && download.getPath().matches("/Mapinfo/V[12]/(?:SL|BR)0000\\.zip")) {
                download = null;
            }
            // The old catalogs link Liechtenstein to Israel. Use the verified V3
            // package as a filename/bounds template, not as a device-ready map.
            // Only its main map passes OfficialMapDownloader's filename filter.
            if ("Liechtenstein".equalsIgnoreCase(name) && download != null
                    && download.getPath().equals("/Mapinfo/asia/IL00002303103RF2JE01202W.map")) {
                download = URI.create("https://qw20191008.oss-us-west-1.aliyuncs.com/8325946d-bcc5-4d00-8a74-67602f23140a");
                size = 1628052;
                version = "20260823";
            }
            result.add(new CatalogNode(text(o, "id"), name, path, size, version, download, children));
        }
        result.sort(BY_NAME);
        return result;
    }

    /** Some catalog links use Windows separators ("Mapinfo\\south-america\\X.map"). */
    private static URI uri(String link) {
        if (link.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(link.trim().replace('\\', '/').replace(" ", "%20"));
            return uri.getScheme() != null && uri.getScheme().startsWith("http") ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A numeric field (numbers or numeric strings); {@code fallback} when missing or not a number. */
    private static long number(JsonObject o, String key, long fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return e.getAsLong();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }
}
