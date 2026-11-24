package io.github.fernandoglatz.igpsmap.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Renders src/test/resources/viewer/tiny.map: a primary road, a residential road and a small lake near
 * Brasília (bbox -15.81,-47.91 .. -15.77,-47.85), written from tiny.osm with tag-igpsport.xml.
 */
class MapTileRendererTest {

    private static final double ROAD_LAT = -15.79;
    private static final double ROAD_LON = -47.88;

    @TempDir
    Path dir;

    private static Path tinyMap() throws URISyntaxException {
        return Path.of(MapTileRendererTest.class.getResource("/viewer/tiny.map").toURI());
    }

    private static TileKey tileAt(double lat, double lon, int zoom) {
        int n = 1 << zoom;
        int x = (int) Math.floor((lon + 180) / 360 * n);
        double sin = Math.sin(Math.toRadians(lat));
        int y = (int) Math.floor((0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * n);
        return new TileKey(x, y, zoom);
    }

    @Test
    void readsTheBoundingBoxFromTheMapHeader() throws Exception {
        try (MapTileRenderer renderer = MapTileRenderer.open(tinyMap())) {
            BoundingBox box = renderer.boundingBox();
            assertEquals(-15.81, box.minLat(), 1e-6);
            assertEquals(-47.91, box.minLon(), 1e-6);
            assertEquals(-15.77, box.maxLat(), 1e-6);
            assertEquals(-47.85, box.maxLon(), 1e-6);
        }
    }

    @Test
    void rendersATileWithTheRoadsOnIt() throws Exception {
        try (MapTileRenderer renderer = MapTileRenderer.open(tinyMap())) {
            int[] pixels = renderer.render(tileAt(ROAD_LAT, ROAD_LON, 14));

            assertEquals(MapTileRenderer.TILE_SIZE * MapTileRenderer.TILE_SIZE, pixels.length);
            long distinct = Arrays.stream(pixels).distinct().count();
            assertTrue(distinct > 5, "expected roads drawn over the background, got " + distinct + " colours");
            assertTrue(Arrays.stream(pixels).allMatch(p -> (p >>> 24) == 0xFF), "tiles are opaque");
        }
    }

    @Test
    void aTileAwayFromTheMapIsPlainBackground() throws Exception {
        try (MapTileRenderer renderer = MapTileRenderer.open(tinyMap())) {
            int[] pixels = renderer.render(tileAt(40.0, 10.0, 14));

            assertEquals(1, Arrays.stream(pixels).distinct().count());
        }
    }

    @Test
    void differentTilesRenderDifferently() throws Exception {
        try (MapTileRenderer renderer = MapTileRenderer.open(tinyMap())) {
            int[] road = renderer.render(tileAt(ROAD_LAT, ROAD_LON, 14));
            int[] empty = renderer.render(tileAt(40.0, 10.0, 14));

            assertNotEquals(Arrays.hashCode(road), Arrays.hashCode(empty));
        }
    }

    @Test
    void aFileThatIsNotAMapIsRejected() throws IOException {
        Path bogus = Files.writeString(dir.resolve("BR01002303102B83FO00N00E.map"), "not a mapsforge file");

        assertThrows(IOException.class, () -> MapTileRenderer.open(bogus));
    }

    @Test
    void aMissingFileIsRejected() {
        assertThrows(IOException.class, () -> MapTileRenderer.open(dir.resolve("missing.map")));
    }

    @Test
    void renderingAfterCloseFails() throws Exception {
        MapTileRenderer renderer = MapTileRenderer.open(tinyMap());
        renderer.close();

        assertThrows(IllegalStateException.class, () -> renderer.render(tileAt(ROAD_LAT, ROAD_LON, 14)));
    }
}
