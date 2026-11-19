package io.github.fernandoglatz.igpsmap.io;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Reads the bounding box from a Mapsforge {@code .map} file header. */
public final class MapHeaderReader {

    private static final byte[] MAGIC = "mapsforge binary OSM".getBytes(StandardCharsets.US_ASCII);
    /** Header size (4) + file version (4) + file size (8) + map date (8). */
    private static final int BYTES_BEFORE_BBOX = 24;

    private MapHeaderReader() {
    }

    public static BoundingBox readBoundingBox(Path mapFile) throws IOException {
        try (InputStream raw = Files.newInputStream(mapFile);
             DataInputStream in = new DataInputStream(raw)) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new InvalidMapFileException("Not a Mapsforge map file: " + mapFile);
            }
            in.skipNBytes(BYTES_BEFORE_BBOX);
            double minLat = in.readInt() / 1_000_000.0;
            double minLon = in.readInt() / 1_000_000.0;
            double maxLat = in.readInt() / 1_000_000.0;
            double maxLon = in.readInt() / 1_000_000.0;
            return new BoundingBox(minLat, minLon, maxLat, maxLon);
        } catch (EOFException e) {
            throw new InvalidMapFileException("Truncated Mapsforge map file: " + mapFile);
        }
    }
}
