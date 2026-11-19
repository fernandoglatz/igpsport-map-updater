package io.github.fernandoglatz.igpsmap.domain;

import java.util.regex.Pattern;

/**
 * IGPSPORT GEOCODE: four 3-character base-36 numbers (xStart, yStart, xSpan-1, ySpan-1)
 * describing a tile rectangle at zoom 13, as IGPSPORT names its map files.
 */
public final class GeoCode {

    private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern FORMAT = Pattern.compile("[0-9A-Z]{12}");

    private GeoCode() {
    }

    public static TileBox toTileBox(BoundingBox box) {
        int xStart = lonToTile(box.minLon());
        int yStart = latToTile(box.maxLat());
        int xEnd = lonToTile(box.maxLon());
        int yEnd = latToTile(box.minLat());
        return new TileBox(xStart, yStart, xEnd - xStart + 1, yEnd - yStart + 1);
    }

    public static String encode(BoundingBox box) {
        return encode(toTileBox(box));
    }

    public static String encode(TileBox tiles) {
        return toBase36(tiles.xStart()) + toBase36(tiles.yStart())
                + toBase36(tiles.xSpan() - 1) + toBase36(tiles.ySpan() - 1);
    }

    public static TileBox decode(String geoCode) {
        if (geoCode == null || !FORMAT.matcher(geoCode).matches()) {
            throw new IllegalArgumentException("Invalid GEOCODE: " + geoCode);
        }
        return new TileBox(
                fromBase36(geoCode.substring(0, 3)),
                fromBase36(geoCode.substring(3, 6)),
                fromBase36(geoCode.substring(6, 9)) + 1,
                fromBase36(geoCode.substring(9, 12)) + 1);
    }

    static int lonToTile(double lon) {
        return (int) ((lon + 180.0) / 360.0 * TileBox.TILES);
    }

    static int latToTile(double lat) {
        double sin = Math.sin(Math.toRadians(lat));
        return (int) ((1.0 - Math.log((1.0 + sin) / (1.0 - sin)) / 2.0 / Math.PI) / 2.0 * TileBox.TILES);
    }

    private static String toBase36(int value) {
        int v = Math.max(value, 0);
        char[] out = new char[3];
        for (int i = 2; i >= 0; i--) {
            out[i] = BASE36.charAt(v % 36);
            v /= 36;
        }
        return new String(out);
    }

    private static int fromBase36(String s) {
        return Integer.parseInt(s, 36);
    }
}
