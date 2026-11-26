package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.viewer.TileKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class TileLayoutTest {

    private static final BoundingBox WORLD = new BoundingBox(-85, -180, 85, 180);
    private static final BoundingBox BRASILIA = new BoundingBox(-16.07, -48.305, -15.48, -47.29);

    @Test
    void picksTheZoomWhoseTilesAreClosestToTheirNativeSize() {
        Viewport v = new Viewport(TileLayout.MAX_SCALE);
        v.fit(BRASILIA, 800, 600);
        int expected = (int) Math.round(Math.log(v.scale() / 256) / Math.log(2));

        TileLayout layout = TileLayout.of(v, 800, 600, WORLD, 18);

        assertEquals(expected, layout.zoom());
        double size = layout.tiles().get(0).size();
        assertTrue(size >= 256 / Math.sqrt(2) - 1e-6 && size <= 256 * Math.sqrt(2) + 1e-6, "tile size " + size);
    }

    @Test
    void tilesCoverTheWholeCanvasWithoutGaps() {
        Viewport v = new Viewport(TileLayout.MAX_SCALE);
        v.fit(BRASILIA, 800, 600);

        TileLayout layout = TileLayout.of(v, 800, 600, WORLD, 18);

        double minX = layout.tiles().stream().mapToDouble(TileLayout.Placed::x).min().orElseThrow();
        double minY = layout.tiles().stream().mapToDouble(TileLayout.Placed::y).min().orElseThrow();
        double maxX = layout.tiles().stream().mapToDouble(t -> t.x() + t.size()).max().orElseThrow();
        double maxY = layout.tiles().stream().mapToDouble(t -> t.y() + t.size()).max().orElseThrow();
        assertTrue(minX <= 0 && minY <= 0 && maxX >= 800 && maxY >= 600, minX + " " + minY + " " + maxX + " " + maxY);
        long columns = layout.tiles().stream().map(t -> t.key().x()).distinct().count();
        long rows = layout.tiles().stream().map(t -> t.key().y()).distinct().count();
        assertEquals(columns * rows, layout.tiles().size());
    }

    @Test
    void tilesAreWhereTheViewportDrawsTheirCorner() {
        Viewport v = new Viewport(TileLayout.MAX_SCALE);
        v.fit(BRASILIA, 800, 600);

        TileLayout layout = TileLayout.of(v, 800, 600, WORLD, 18);

        TileLayout.Placed first = layout.tiles().get(0);
        double n = 1 << layout.zoom();
        assertEquals(v.screenXOfWorld(first.key().x() / n), first.x(), 1e-6);
        assertEquals(v.screenYOfWorld(first.key().y() / n), first.y(), 1e-6);
    }

    @Test
    void onlyTilesTouchingTheMapAreListed() {
        Viewport v = new Viewport(TileLayout.MAX_SCALE);
        v.fit(new BoundingBox(-34, -74, 5, -34), 800, 600); // all of Brazil in view

        List<TileLayout.Placed> tiles = TileLayout.of(v, 800, 600, BRASILIA, 18).tiles();

        assertTrue(!tiles.isEmpty() && tiles.size() <= 4, tiles.size() + " tiles");
        assertTrue(tiles.stream().allMatch(t -> t.key().intersects(BRASILIA)));
    }

    @Test
    void zoomStopsAtTheMaximumAndTilesAreDrawnLarger() {
        Viewport v = new Viewport(TileLayout.MAX_SCALE);
        v.fit(new BoundingBox(-15.7901, -47.8801, -15.79, -47.88), 800, 600); // a few metres

        TileLayout layout = TileLayout.of(v, 800, 600, WORLD, 16);

        assertEquals(16, layout.zoom());
        assertTrue(layout.tiles().get(0).size() > 256 * 2);
    }

    @Test
    void nothingToDrawWhenTheMapIsOffScreenOrTheCanvasIsEmpty() {
        Viewport v = new Viewport(TileLayout.MAX_SCALE);
        v.fit(new BoundingBox(40, 0, 50, 10), 800, 600); // Europe

        assertTrue(TileLayout.of(v, 800, 600, BRASILIA, 18).tiles().isEmpty());
        assertTrue(TileLayout.of(v, 0, 0, WORLD, 18).tiles().isEmpty());
    }

    @Test
    void zoomedOutTilesStayInsideTheWorld() {
        Viewport v = new Viewport(TileLayout.MAX_SCALE);
        v.fit(WORLD, 4000, 4000); // world smaller than the canvas: tiles must not wrap or go negative

        TileLayout layout = TileLayout.of(v, 4000, 4000, WORLD, 18);

        int n = 1 << layout.zoom();
        assertTrue(layout.tiles().stream().allMatch(t -> t.key().x() >= 0 && t.key().x() < n
                && t.key().y() >= 0 && t.key().y() < n));
        assertEquals((long) n * n, layout.tiles().size());
    }
}
