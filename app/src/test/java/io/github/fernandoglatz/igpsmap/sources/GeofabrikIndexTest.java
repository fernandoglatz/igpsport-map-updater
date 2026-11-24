package io.github.fernandoglatz.igpsmap.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeofabrikIndexTest {

    static String miniIndexJson() throws IOException {
        try (InputStream in = GeofabrikIndexTest.class.getResourceAsStream("/geofabrik-mini.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static GeofabrikIndex miniIndex() throws IOException {
        return GeofabrikIndex.parse(miniIndexJson());
    }

    @Test
    void findsCountryByIsoCode() throws IOException {
        GeofabrikRegion brazil = miniIndex().country("BR").orElseThrow();
        assertEquals("brazil", brazil.id());
        assertEquals(URI.create("https://download.geofabrik.de/south-america/brazil-latest.osm.pbf"), brazil.pbfUrl());
        assertEquals(40.0 * 39.0, brazil.area(), 1e-9);
    }

    @Test
    void unknownCountryIsEmpty() throws IOException {
        assertTrue(miniIndex().country("XX").isEmpty());
    }

    @Test
    void featuresWithoutPbfAreSkipped() throws IOException {
        assertTrue(miniIndex().country("XY").isEmpty());
    }

    @Test
    void subtreeIncludesCountryAndDescendants() throws IOException {
        List<String> ids = miniIndex().subtree("brazil").stream().map(GeofabrikRegion::id).sorted().toList();
        assertEquals(List.of("brazil", "centro-oeste", "sudeste", "sul"), ids);
    }

    @Test
    void pathOfStripsHostAndSuffix() throws IOException {
        assertEquals("south-america/brazil", GeofabrikIndex.pathOf(miniIndex().country("BR").orElseThrow()));
        assertEquals("europe/united-kingdom", GeofabrikIndex.pathOf(miniIndex().country("GB").orElseThrow()));
    }

    @Test
    void parsesPlainPolygonGeometry() {
        String json = """
                {"type":"FeatureCollection","features":[{"type":"Feature",
                 "properties":{"id":"p","name":"P","iso3166-1:alpha2":["PT"],"urls":{"pbf":"https://x/europe/p-latest.osm.pbf"}},
                 "geometry":{"type":"Polygon","coordinates":[[[0,0],[2,0],[2,2],[0,2],[0,0]]]}}]}
                """;
        assertEquals(4.0, GeofabrikIndex.parse(json).country("PT").orElseThrow().area(), 1e-9);
    }

    @Test
    void aNullParentOrNameIsTreatedAsMissing() {
        // Since November 2026 Geofabrik's index sends "parent": null for some top-level regions.
        String json = """
                {"type":"FeatureCollection","features":[{"type":"Feature",
                 "properties":{"id":"p","parent":null,"name":null,"iso3166-1:alpha2":["PT"],
                  "urls":{"pbf":"https://x/europe/p-latest.osm.pbf"}},
                 "geometry":{"type":"Polygon","coordinates":[[[0,0],[2,0],[2,2],[0,2],[0,0]]]}}]}
                """;

        GeofabrikRegion region = GeofabrikIndex.parse(json).country("PT").orElseThrow();

        assertEquals(null, region.parent());
        assertEquals("p", region.name());
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> GeofabrikIndex.parse("<html>oops</html>"));
    }
}
