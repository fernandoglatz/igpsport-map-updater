package io.github.fernandoglatz.igpsmap.sources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.fernandoglatz.igpsmap.net.DiskCache;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import org.locationtech.jts.geom.Geometry;

/** Parsed {@code https://download.geofabrik.de/index-v1.json}. */
public final class GeofabrikIndex {

    public static final URI INDEX_URL = URI.create("https://download.geofabrik.de/index-v1.json");

    private final List<GeofabrikRegion> regions;
    private final Map<String, List<String>> specialRegions;
    private static final Map<String, String> COUNTRY_EXTRACTS = Map.of(
            "JE", "guernsey-jersey", "RE", "reunion", "BM", "bermuda",
            "MP", "american-oceania", "SA", "gcc-states",
            "AW", "central-america", "TT", "central-america");

    private GeofabrikIndex(List<GeofabrikRegion> regions, Map<String, List<String>> specialRegions) {
        this.regions = List.copyOf(regions);
        this.specialRegions = Map.copyOf(specialRegions);
    }

    public static GeofabrikIndex load(HttpFetcher fetcher, DiskCache cache) throws IOException {
        return parse(cache.getOrFetch(INDEX_URL.toString(), () -> fetcher.getText(INDEX_URL)));
    }

    public static GeofabrikIndex parse(String json) {
        try {
            JsonArray features = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("features");
            List<GeofabrikRegion> regions = new ArrayList<>();
            Map<String, List<String>> specialRegions = new LinkedHashMap<>();
            for (JsonElement element : features) {
                JsonObject feature = element.getAsJsonObject();
                JsonObject props = feature.getAsJsonObject("properties");
                JsonObject urls = props.has("urls") ? props.getAsJsonObject("urls") : new JsonObject();
                if (!urls.has("pbf") || !feature.has("geometry") || feature.get("geometry").isJsonNull()) {
                    continue;
                }
                List<String> iso = new ArrayList<>();
                JsonElement isoCodes = props.get("iso3166-1:alpha2");
                if (isoCodes != null && !isoCodes.isJsonNull()) {
                    if (isoCodes.isJsonArray()) {
                        isoCodes.getAsJsonArray().forEach(e -> addCodes(iso, e));
                    } else {
                        addCodes(iso, isoCodes);
                    }
                }
                String id = props.get("id").getAsString();
                JsonElement extra = props.get("special_regions");
                if (extra != null && extra.isJsonArray()) {
                    List<String> ids = new ArrayList<>();
                    extra.getAsJsonArray().forEach(e -> { if (!e.isJsonNull()) ids.add(e.getAsString()); });
                    specialRegions.put(id, List.copyOf(ids));
                }
                regions.add(new GeofabrikRegion(
                        id,
                        optionalString(props, "parent"),
                        Optional.ofNullable(optionalString(props, "name")).orElse(id),
                        List.copyOf(new LinkedHashSet<>(iso)),
                        URI.create(urls.get("pbf").getAsString()),
                        GeoJson.geometry(feature.getAsJsonObject("geometry"))));
            }
            return new GeofabrikIndex(regions, specialRegions);
        } catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException e) {
            throw new IllegalArgumentException("Invalid Geofabrik index", e);
        }
    }

    /** The property as a string, or null when it is absent or JSON null (Geofabrik sends both). */
    private static String optionalString(JsonObject props, String key) {
        JsonElement value = props.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    public List<GeofabrikRegion> regions() {
        return regions;
    }

    /** The largest region tagged with this ISO 3166-1 alpha-2 code. */
    public Optional<GeofabrikRegion> country(String countryCode) {
        Optional<GeofabrikRegion> direct = regions.stream()
                .filter(r -> r.iso2().contains(countryCode))
                .max(Comparator.comparingDouble(GeofabrikRegion::area));
        if (direct.isPresent()) return direct;
        String id = COUNTRY_EXTRACTS.get(countryCode);
        return regions.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** The region and all of its descendants. */
    public List<GeofabrikRegion> subtree(String id) {
        List<GeofabrikRegion> out = new ArrayList<>();
        java.util.Set<String> seen = new LinkedHashSet<>();
        regions.stream().filter(r -> r.id().equals(id)).forEach(r -> { out.add(r); seen.add(r.id()); });
        for (int i = 0; i < out.size(); i++) {
            String parent = out.get(i).id();
            List<String> extras = specialRegions.getOrDefault(parent, List.of());
            regions.stream().filter(r -> parent.equals(r.parent()) || extras.contains(r.id()))
                    .filter(r -> seen.add(r.id())).forEach(out::add);
        }
        return out;
    }

    private static void addCodes(List<String> out, JsonElement element) {
        if (element.isJsonNull()) return;
        for (String code : element.getAsString().split(",")) {
            String normalized = code.strip().toUpperCase(Locale.ROOT);
            if (!normalized.isEmpty()) out.add(normalized);
        }
    }

    /** URL path of the extract without host and suffix, e.g. {@code south-america/brazil}. */
    public static String pathOf(GeofabrikRegion region) {
        String path = region.pbfUrl().getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path.replaceFirst("-latest\\.osm\\.pbf$", "");
    }
}
