package io.github.fernandoglatz.igpsmap.viewer;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;

/** A slippy-map tile: column {@code x}, row {@code y} (north at 0) at {@code zoom}. */
public record TileKey(int x, int y, int zoom) {

    /** Deeper than any zoom Mapsforge renders; keeps {@code 1 << zoom} in range. */
    private static final int MAX_ZOOM = 30;

    public TileKey {
        if (zoom < 0 || zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("Zoom out of range: " + zoom);
        }
        int n = 1 << zoom;
        if (x < 0 || y < 0 || x >= n || y >= n) {
            throw new IllegalArgumentException("Tile outside the world at zoom " + zoom + ": " + x + "," + y);
        }
    }

    /** The tile one zoom level up that contains this one. */
    public TileKey parent() {
        if (zoom == 0) {
            throw new IllegalStateException("The zoom 0 tile has no parent");
        }
        return new TileKey(x >> 1, y >> 1, zoom - 1);
    }

    /** Whether this tile's area overlaps {@code box} (edges touching count). */
    public boolean intersects(BoundingBox box) {
        double n = 1 << zoom;
        double minLon = x / n * 360.0 - 180.0;
        double maxLon = (x + 1) / n * 360.0 - 180.0;
        double maxLat = latOfRow(y / n);
        double minLat = latOfRow((y + 1) / n);
        return box.minLon() <= maxLon && box.maxLon() >= minLon && box.minLat() <= maxLat && box.maxLat() >= minLat;
    }

    private static double latOfRow(double worldY) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * worldY))));
    }
}
