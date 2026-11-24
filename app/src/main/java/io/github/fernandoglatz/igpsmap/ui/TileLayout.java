package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.viewer.MapTileRenderer;
import io.github.fernandoglatz.igpsmap.viewer.TileKey;
import java.util.ArrayList;
import java.util.List;

/**
 * The tiles to draw for a viewport: the zoom level whose tiles come closest to their native size, and
 * each visible tile (limited to those touching the map) with its position and size on screen.
 */
record TileLayout(int zoom, List<Placed> tiles) {

    /** Highest zoom tiles are rendered at; closer than that, tiles are drawn enlarged. */
    static final int MAX_TILE_ZOOM = 18;
    /** Viewer zoom limit: two levels past {@link #MAX_TILE_ZOOM}. */
    static final double MAX_SCALE = (double) MapTileRenderer.TILE_SIZE * (1 << (MAX_TILE_ZOOM + 2));

    record Placed(TileKey key, double x, double y, double size) {
    }

    static TileLayout of(Viewport viewport, double width, double height, BoundingBox limit, int maxZoom) {
        double exact = Math.log(viewport.scale() / MapTileRenderer.TILE_SIZE) / Math.log(2);
        int zoom = (int) Math.max(0, Math.min(maxZoom, Math.round(exact)));
        if (width <= 0 || height <= 0) {
            return new TileLayout(zoom, List.of());
        }
        int n = 1 << zoom;
        int x0 = clampTile(Math.floor(viewport.worldXAt(0) * n), n);
        int x1 = clampTile(Math.floor(viewport.worldXAt(width) * n), n);
        int y0 = clampTile(Math.floor(viewport.worldYAt(0) * n), n);
        int y1 = clampTile(Math.floor(viewport.worldYAt(height) * n), n);
        double size = viewport.scale() / n;
        List<Placed> tiles = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                TileKey key = new TileKey(x, y, zoom);
                double sx = viewport.screenXOfWorld((double) x / n);
                double sy = viewport.screenYOfWorld((double) y / n);
                if (sx + size < 0 || sy + size < 0 || sx > width || sy > height || !key.intersects(limit)) {
                    continue; // outside the canvas (world smaller than the view) or away from the map
                }
                tiles.add(new Placed(key, sx, sy, size));
            }
        }
        return new TileLayout(zoom, List.copyOf(tiles));
    }

    private static int clampTile(double value, int n) {
        return (int) Math.max(0, Math.min(n - 1, value));
    }
}
