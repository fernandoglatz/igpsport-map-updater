package io.github.fernandoglatz.igpsmap.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.fernandoglatz.igpsmap.sources.GeoJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.locationtech.jts.geom.Geometry;

/**
 * Background map from bundled Natural Earth data (public domain): countries at two levels of
 * detail plus lakes. See resources/basemap/README.md.
 */
final class BaseMap {

    /** Viewport scale (pixels per world width) from which the detailed data is drawn: zoom 3. */
    static final double DETAIL_SCALE = 2048;

    record Country(String name, Geometry geometry, double labelLon, double labelLat, double minLabelZoom) {
    }

    private static volatile BaseMap bundled;

    private final List<Country> coarse;
    private final List<Country> detailed;
    private final List<Geometry> lakes;

    private BaseMap(List<Country> coarse, List<Country> detailed, List<Geometry> lakes) {
        this.coarse = List.copyOf(coarse);
        this.detailed = List.copyOf(detailed);
        this.lakes = List.copyOf(lakes);
    }

    /** Loads the bundled data once (about 1 MB compressed); call off the FX thread. */
    static BaseMap bundled() {
        BaseMap map = bundled;
        if (map == null) {
            synchronized (BaseMap.class) {
                if (bundled == null) {
                    bundled = new BaseMap(
                            parseCountries(resource("ne_110m_admin_0_countries.json.gz")),
                            parseCountries(resource("ne_50m_admin_0_countries.json.gz")),
                            parseCountries(resource("ne_50m_lakes.json.gz")).stream().map(Country::geometry).toList());
                }
                map = bundled;
            }
        }
        return map;
    }

    List<Country> countries(double scale) {
        return scale >= DETAIL_SCALE ? detailed : coarse;
    }

    List<Geometry> lakes(double scale) {
        return scale >= DETAIL_SCALE ? lakes : List.of();
    }

    /** Slippy-map zoom level equivalent of a viewport scale (256 px tiles). */
    static double zoom(double scale) {
        return Math.log(scale / 256.0) / Math.log(2);
    }

    static boolean labelVisible(Country country, double scale) {
        return !country.name().isEmpty() && zoom(scale) >= country.minLabelZoom();
    }

    static List<Country> parseCountries(InputStream json) {
        try (InputStreamReader reader = new InputStreamReader(json, StandardCharsets.UTF_8)) {
            List<Country> out = new ArrayList<>();
            for (JsonElement element : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("features")) {
                JsonObject feature = element.getAsJsonObject();
                JsonObject p = feature.has("properties") ? feature.getAsJsonObject("properties") : new JsonObject();
                Geometry geometry = GeoJson.geometry(feature.getAsJsonObject("geometry"));
                double labelLon = p.has("label_x") ? p.get("label_x").getAsDouble() : geometry.getCentroid().getX();
                double labelLat = p.has("label_y") ? p.get("label_y").getAsDouble() : geometry.getCentroid().getY();
                out.add(new Country(p.has("name") ? p.get("name").getAsString() : "", geometry, labelLon, labelLat,
                        p.has("min_label") ? p.get("min_label").getAsDouble() : 99));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String name) {
        try {
            InputStream in = BaseMap.class.getResourceAsStream("/basemap/" + name);
            if (in == null) {
                throw new IllegalStateException("Missing bundled base map: " + name);
            }
            return new GZIPInputStream(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
