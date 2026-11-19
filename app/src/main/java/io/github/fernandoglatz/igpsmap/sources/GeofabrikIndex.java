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
import org.locationtech.jts.geom.Geometry;

/** Parsed {@code https://download.geofabrik.de/index-v1.json}. */
public final class GeofabrikIndex {

    public static final URI INDEX_URL = URI.create("https://download.geofabrik.de/index-v1.json");

    private final List<GeofabrikRegion> regions;

    private GeofabrikIndex(List<GeofabrikRegion> regions) {
        this.regions = List.copyOf(regions);
    }

    public static GeofabrikIndex load(HttpFetcher fetcher, DiskCache cache) throws IOException {
        return parse(cache.getOrFetch(INDEX_URL.toString(), () -> fetcher.getText(INDEX_URL)));
    }

    public static GeofabrikIndex parse(String json) {
        try {
            JsonArray features = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("features");
            List<GeofabrikRegion> regions = new ArrayList<>();
            for (JsonElement element : features) {
                JsonObject feature = element.getAsJsonObject();
                JsonObject props = feature.getAsJsonObject("properties");
                JsonObject urls = props.has("urls") ? props.getAsJsonObject("urls") : new JsonObject();
                if (!urls.has("pbf") || !feature.has("geometry") || feature.get("geometry").isJsonNull()) {
                    continue;
                }
                List<String> iso = new ArrayList<>();
                if (props.has("iso3166-1:alpha2")) {
                    props.getAsJsonArray("iso3166-1:alpha2").forEach(e -> iso.add(e.getAsString()));
                }
                regions.add(new GeofabrikRegion(
                        props.get("id").getAsString(),
                        props.has("parent") ? props.get("parent").getAsString() : null,
                        props.has("name") ? props.get("name").getAsString() : props.get("id").getAsString(),
                        List.copyOf(iso),
                        URI.create(urls.get("pbf").getAsString()),
                        GeoJson.geometry(feature.getAsJsonObject("geometry"))));
            }
            return new GeofabrikIndex(regions);
        } catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException e) {
            throw new IllegalArgumentException("Invalid Geofabrik index", e);
        }
    }

    public List<GeofabrikRegion> regions() {
        return regions;
    }

    /** The largest region tagged with this ISO 3166-1 alpha-2 code. */
    public Optional<GeofabrikRegion> country(String countryCode) {
        return regions.stream()
                .filter(r -> r.iso2().contains(countryCode))
                .max(Comparator.comparingDouble(GeofabrikRegion::area));
    }

    /** The region and all of its descendants. */
    public List<GeofabrikRegion> subtree(String id) {
        List<GeofabrikRegion> out = new ArrayList<>();
        regions.stream().filter(r -> r.id().equals(id)).forEach(out::add);
        for (int i = 0; i < out.size(); i++) {
            String parent = out.get(i).id();
            regions.stream().filter(r -> parent.equals(r.parent())).forEach(out::add);
        }
        return out;
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
