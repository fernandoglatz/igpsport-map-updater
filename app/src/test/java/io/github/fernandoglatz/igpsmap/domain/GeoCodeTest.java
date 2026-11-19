package io.github.fernandoglatz.igpsmap.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeoCodeTest {

    @Test
    void encodesDistritoFederalBoundingBoxFromExistingMap() {
        assertEquals("2B83FO00N00E", GeoCode.encode(new BoundingBox(-16.07, -48.305, -15.48, -47.29)));
    }

    @Test
    void decodesGeoCodeIntoTiles() {
        assertEquals(new TileBox(2996, 4452, 24, 15), GeoCode.decode("2B83FO00N00E"));
    }

    @Test
    void roundTripsThroughBoundingBox() {
        TileBox tiles = GeoCode.decode("2CA3B506006H");
        assertEquals("2CA3B506006H", GeoCode.encode(shrinkByHalfTile(tiles.toBoundingBox())));
    }

    @Test
    void clampsNegativeTilesToZero() {
        assertTrue(GeoCode.encode(new BoundingBox(-10, -181, 10, -170)).startsWith("000"));
    }

    @Test
    void rejectsBadGeoCode() {
        assertThrows(IllegalArgumentException.class, () -> GeoCode.decode("2B83FO00N00"));
        assertThrows(IllegalArgumentException.class, () -> GeoCode.decode("2b83fo00n00!"));
        assertThrows(IllegalArgumentException.class, () -> GeoCode.decode(null));
    }

    @Test
    void iouIsOneForEqualBoxes() {
        TileBox box = new TileBox(0, 0, 2, 2);
        assertEquals(1.0, box.iou(box), 1e-9);
    }

    @Test
    void iouIsZeroForDisjointBoxes() {
        assertEquals(0.0, new TileBox(0, 0, 2, 2).iou(new TileBox(10, 10, 2, 2)), 1e-9);
    }

    @Test
    void iouOfPartialOverlap() {
        // intersection 1x2 = 2, union 4 + 4 - 2 = 6
        assertEquals(2.0 / 6.0, new TileBox(0, 0, 2, 2).iou(new TileBox(1, 0, 2, 2)), 1e-9);
    }

    private static BoundingBox shrinkByHalfTile(BoundingBox box) {
        double lonStep = 360.0 / TileBox.TILES / 2;
        double latStep = (box.maxLat() - box.minLat()) / 1000;
        return new BoundingBox(box.minLat() + latStep, box.minLon() + lonStep,
                box.maxLat() - latStep, box.maxLon() - lonStep);
    }
}
