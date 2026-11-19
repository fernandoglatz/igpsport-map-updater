package io.github.fernandoglatz.igpsmap.sources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.io.PolyFile;
import io.github.fernandoglatz.igpsmap.net.DiskCache;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenStreetMap administrative boundaries (admin levels 2-6) of a country, found with one Overpass
 * query and served as {@code .poly} files by polygons.openstreetmap.fr. Used for regions that
 * neither openstreetmap.fr's static tree nor Geofabrik cover, e.g. Japanese prefectures.
 */
public final class OsmBoundarySource {

    public static final URI DEFAULT_OVERPASS = URI.create("https://overpass-api.de/api/interpreter");
    /**
     * Only the main instance: public mirrors timed out or answered 504 in every live test, and
     * waiting for them made busy periods several minutes slower.
     */
    public static final List<URI> DEFAULT_OVERPASS_ENDPOINTS = List.of(DEFAULT_OVERPASS);
    /** Waits between rounds of attempts when every endpoint is busy. */
    public static final List<java.time.Duration> DEFAULT_BACKOFFS =
            List.of(java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(30));
    private static final java.util.Set<Integer> BUSY = java.util.Set.of(429, 502, 503, 504);
    public static final URI DEFAULT_POLYGONS = URI.create("https://polygons.openstreetmap.fr/");

    private final HttpFetcher fetcher;
    private final DiskCache cache;
    private final List<URI> endpoints;
    private final URI polygons;
    private final List<java.time.Duration> backoffs;

    public OsmBoundarySource(HttpFetcher fetcher, DiskCache cache, URI overpass, URI polygons) {
        this(fetcher, cache, List.of(overpass), polygons, List.of());
    }

    public OsmBoundarySource(HttpFetcher fetcher, DiskCache cache, List<URI> endpoints, URI polygons,
                             List<java.time.Duration> backoffs) {
        this.fetcher = fetcher;
        this.cache = cache;
        this.endpoints = List.copyOf(endpoints);
        this.polygons = polygons;
        this.backoffs = List.copyOf(backoffs);
    }

    public List<PolyCandidate> boundaries(String countryCode) throws IOException {
        String query = query(countryCode);
        String json = cache.getOrFetch("overpass:" + query, () -> {
            String body = fetchWithRetries(query);
            parse(body, polygons); // never cache an error answer
            return body;
        });
        try {
            return parse(json, polygons);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Tries every endpoint; when all are busy (429/5xx/timeout), waits and tries again for each
     * backoff. Other errors (e.g. 400) fail immediately.
     */
    private String fetchWithRetries(String query) throws IOException {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        IOException last = null;
        for (int round = 0; round <= backoffs.size(); round++) {
            if (round > 0) {
                sleep(backoffs.get(round - 1));
            }
            for (URI endpoint : endpoints) {
                try {
                    return fetcher.getText(URI.create(endpoint + "?data=" + encoded));
                } catch (io.github.fernandoglatz.igpsmap.net.HttpStatusException e) {
                    if (!BUSY.contains(e.status())) {
                        throw e;
                    }
                    last = e;
                } catch (java.net.http.HttpTimeoutException e) {
                    last = e;
                }
            }
        }
        throw new IOException("Overpass is busy, try \"Refresh sources\" later (" + last.getMessage() + ")", last);
    }

    private static void sleep(java.time.Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Overpass", e);
        }
    }

    /** Downloads (and caches) the boundary's real polygon, asking the site to build it if needed. */
    public org.locationtech.jts.geom.Geometry geometry(PolyCandidate candidate) throws IOException {
        URI url = candidate.url();
        String text = cache.getOrFetch(url.toString(), () -> {
            String body = fetcher.getText(url);
            if (!isPoly(body)) {
                // polygons.openstreetmap.fr builds a relation's polygon when index.py is requested.
                String query = url.getRawQuery() == null ? "" : url.getRawQuery();
                java.util.regex.Matcher id = java.util.regex.Pattern.compile("(?:^|&)id=(\\d+)").matcher(query);
                if (id.find()) {
                    fetcher.getText(url.resolve("index.py?id=" + id.group(1)));
                    body = fetcher.getText(url);
                }
            }
            if (!isPoly(body)) {
                throw new IOException("No polygon available for " + candidate.name());
            }
            return body;
        });
        return PolyFile.parse(text);
    }

    private static boolean isPoly(String text) {
        try {
            PolyFile.parse(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String query(String countryCode) {
        return "[out:json][timeout:180];"
                + "area[\"ISO3166-1\"=\"" + countryCode + "\"][\"admin_level\"=\"2\"]->.country;"
                + "rel(area.country)[\"boundary\"=\"administrative\"][\"admin_level\"~\"^[2-6]$\"];"
                + "out ids tags bb;";
    }

    static List<PolyCandidate> parse(String json, URI polygons) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("remark") && root.get("remark").getAsString().toLowerCase().contains("error")) {
                throw new IllegalArgumentException("Overpass: " + root.get("remark").getAsString());
            }
            List<PolyCandidate> out = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("elements")) {
                JsonObject relation = element.getAsJsonObject();
                if (!relation.has("bounds") || !relation.has("id")) {
                    continue;
                }
                JsonObject b = relation.getAsJsonObject("bounds");
                BoundingBox box = new BoundingBox(b.get("minlat").getAsDouble(), b.get("minlon").getAsDouble(),
                        b.get("maxlat").getAsDouble(), b.get("maxlon").getAsDouble());
                long id = relation.get("id").getAsLong();
                out.add(new PolyCandidate(
                        polygons.resolve("get_poly.py?id=" + id + "&params=0"),
                        label(relation, id),
                        PolyFile.parse(PolyFile.fromBoundingBox(box, "osm-" + id)),
                        GeoCode.toTileBox(box)));
            }
            return out;
        } catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException
                 | UnsupportedOperationException e) {
            throw new IllegalArgumentException("Invalid Overpass response", e);
        }
    }

    private static String label(JsonObject relation, long id) {
        JsonObject tags = relation.has("tags") ? relation.getAsJsonObject("tags") : new JsonObject();
        String name = tags.has("name:en") ? tags.get("name:en").getAsString()
                : tags.has("name") ? tags.get("name").getAsString() : "relation " + id;
        return name + " (OSM)";
    }
}
