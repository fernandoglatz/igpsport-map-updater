package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import org.junit.jupiter.api.Test;

class ViewportTest {

    @Test
    void projectsWithWebMercator() {
        assertEquals(0.5, Viewport.worldX(0), 1e-12);
        assertEquals(0.0, Viewport.worldX(-180), 1e-12);
        assertEquals(0.5, Viewport.worldY(0), 1e-12);
        assertTrue(Viewport.worldY(60) < Viewport.worldY(0)); // north is up
        assertEquals(45.0, Viewport.lat(Viewport.worldY(45)), 1e-9);
        assertEquals(-47.9, Viewport.lon(Viewport.worldX(-47.9)), 1e-9);
    }

    @Test
    void fitShowsTheWholeBoxCenteredWithAMargin() {
        Viewport v = new Viewport();
        BoundingBox brazil = new BoundingBox(-34, -74, 5, -34);
        v.fit(brazil, 800, 600);

        double left = v.screenX(-74), right = v.screenX(-34);
        double top = v.screenY(5), bottom = v.screenY(-34);
        assertTrue(left >= 0 && right <= 800 && top >= 0 && bottom <= 600, left + " " + right + " " + top + " " + bottom);
        assertEquals(400, (left + right) / 2, 1e-6);
        assertEquals(300, (top + bottom) / 2, 1e-6);
        assertTrue(bottom - top > 500, "box should fill most of the height");
    }

    @Test
    void zoomKeepsThePointUnderTheCursorFixed() {
        Viewport v = new Viewport();
        v.fit(new BoundingBox(-34, -74, 5, -34), 800, 600);
        double lon = v.lonAt(200), lat = v.latAt(150);
        double before = v.scale();

        v.zoomAt(200, 150, 2.0);

        assertEquals(200, v.screenX(lon), 1e-6);
        assertEquals(150, v.screenY(lat), 1e-6);
        assertEquals(before * 2.0, v.scale(), 1e-6);
    }

    @Test
    void zoomIsClampedBetweenWorldAndStreetLevel() {
        Viewport v = new Viewport();
        v.fit(new BoundingBox(-34, -74, 5, -34), 800, 600);
        for (int i = 0; i < 100; i++) {
            v.zoomAt(400, 300, 0.5);
        }
        assertTrue(v.scale() >= Viewport.MIN_SCALE);
        for (int i = 0; i < 100; i++) {
            v.zoomAt(400, 300, 2.0);
        }
        assertTrue(v.scale() <= Viewport.MAX_SCALE);
    }

    @Test
    void panMovesByScreenPixels() {
        Viewport v = new Viewport();
        v.fit(new BoundingBox(-34, -74, 5, -34), 800, 600);
        double x = v.screenX(-50), y = v.screenY(-20);
        v.pan(30, -10);
        assertEquals(x + 30, v.screenX(-50), 1e-6);
        assertEquals(y - 10, v.screenY(-20), 1e-6);
    }
    @Test
    void aViewerViewportCanZoomFurtherThanTheDefault() {
        Viewport v = new Viewport(Viewport.MAX_SCALE * 8);
        v.fit(new BoundingBox(-34, -74, 5, -34), 800, 600);
        for (int i = 0; i < 100; i++) {
            v.zoomAt(400, 300, 2.0);
        }
        assertEquals(Viewport.MAX_SCALE * 8, v.scale(), 1e-6);
    }

    @Test
    void maximumScaleBelowTheMinimumIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Viewport(Viewport.MIN_SCALE / 2));
    }

    @Test
    void convertsBetweenWorldAndScreenCoordinates() {
        Viewport v = new Viewport();
        v.fit(new BoundingBox(-34, -74, 5, -34), 800, 600);

        assertEquals(v.screenX(-50), v.screenXOfWorld(Viewport.worldX(-50)), 1e-9);
        assertEquals(v.screenY(-20), v.screenYOfWorld(Viewport.worldY(-20)), 1e-9);
        assertEquals(Viewport.worldX(-50), v.worldXAt(v.screenX(-50)), 1e-12);
        assertEquals(Viewport.worldY(-20), v.worldYAt(v.screenY(-20)), 1e-12);
    }
}
