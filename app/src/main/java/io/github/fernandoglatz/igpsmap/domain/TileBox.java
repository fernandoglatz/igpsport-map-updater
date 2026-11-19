package io.github.fernandoglatz.igpsmap.domain;

/**
 * Rectangle of slippy-map tiles at zoom 13, as encoded in an IGPSPORT GEOCODE.
 * {@code xSpan}/{@code ySpan} are tile counts (at least 1).
 */
public record TileBox(int xStart, int yStart, int xSpan, int ySpan) {

    /** Tiles per side at zoom 13. */
    public static final int TILES = 1 << 13;

    public BoundingBox toBoundingBox() {
        return new BoundingBox(
                tileToLat(yStart + ySpan),
                tileToLon(xStart),
                tileToLat(yStart),
                tileToLon(xStart + xSpan));
    }

    /** Intersection over union of the two rectangles, measured in tiles. */
    public double iou(TileBox other) {
        long ix = Math.max(0, Math.min(xStart + xSpan, other.xStart + other.xSpan) - Math.max(xStart, other.xStart));
        long iy = Math.max(0, Math.min(yStart + ySpan, other.yStart + other.ySpan) - Math.max(yStart, other.yStart));
        long intersection = ix * iy;
        long union = (long) xSpan * ySpan + (long) other.xSpan * other.ySpan - intersection;
        return union <= 0 ? 0.0 : (double) intersection / union;
    }

    private static double tileToLon(int x) {
        return (double) x / TILES * 360.0 - 180.0;
    }

    private static double tileToLat(int y) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / TILES))));
    }
}
