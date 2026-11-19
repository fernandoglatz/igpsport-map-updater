package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;

/**
 * Web Mercator view of the world: world coordinates are 0..1 on both axes (north up), mapped to
 * screen pixels by a scale and an offset. Not thread-safe; used on the FX thread.
 */
final class Viewport {

    /** Whole world about 200 px wide at most zoomed out. */
    static final double MIN_SCALE = 200;
    /** Far beyond zoom 13 tiles; enough to inspect single-tile maps. */
    static final double MAX_SCALE = 1 << 24;
    private static final double MAX_LAT = 85.05112878;
    private static final double FIT_MARGIN = 0.9;

    private double scale = MIN_SCALE;
    private double offsetX;
    private double offsetY;

    static double worldX(double lon) {
        return (lon + 180.0) / 360.0;
    }

    static double worldY(double lat) {
        double clamped = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        double sin = Math.sin(Math.toRadians(clamped));
        return 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
    }

    static double lon(double worldX) {
        return worldX * 360.0 - 180.0;
    }

    static double lat(double worldY) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * worldY))));
    }

    double scale() {
        return scale;
    }

    double screenX(double lon) {
        return offsetX + worldX(lon) * scale;
    }

    double screenY(double lat) {
        return offsetY + worldY(lat) * scale;
    }

    double lonAt(double screenX) {
        return lon((screenX - offsetX) / scale);
    }

    double latAt(double screenY) {
        return lat((screenY - offsetY) / scale);
    }

    /** Shows the whole box centred, filling about 90% of the smaller dimension. */
    void fit(BoundingBox box, double width, double height) {
        double x0 = worldX(box.minLon()), x1 = worldX(box.maxLon());
        double y0 = worldY(box.maxLat()), y1 = worldY(box.minLat());
        double w = Math.max(x1 - x0, 1e-6), h = Math.max(y1 - y0, 1e-6);
        scale = clamp(Math.min(width / w, height / h) * FIT_MARGIN);
        offsetX = width / 2 - (x0 + x1) / 2 * scale;
        offsetY = height / 2 - (y0 + y1) / 2 * scale;
    }

    /** Zooms by {@code factor}, keeping the world point under (screenX, screenY) in place. */
    void zoomAt(double screenX, double screenY, double factor) {
        double wx = (screenX - offsetX) / scale;
        double wy = (screenY - offsetY) / scale;
        scale = clamp(scale * factor);
        offsetX = screenX - wx * scale;
        offsetY = screenY - wy * scale;
    }

    void pan(double dx, double dy) {
        offsetX += dx;
        offsetY += dy;
    }

    private static double clamp(double s) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    }
}
