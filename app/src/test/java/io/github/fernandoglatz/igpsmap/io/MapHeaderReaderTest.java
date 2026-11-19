package io.github.fernandoglatz.igpsmap.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapHeaderReaderTest {

    @TempDir
    Path dir;

    /** Builds a minimal Mapsforge header with the given bounding box in micro-degrees. */
    static byte[] mapHeader(String magic, int minLat, int minLon, int maxLat, int maxLon) {
        ByteBuffer buf = ByteBuffer.allocate(80);
        buf.put(magic.getBytes(StandardCharsets.US_ASCII));
        buf.putInt(499).putInt(3).putLong(5_750_000L).putLong(1_768_217_000_000L);
        buf.putInt(minLat).putInt(minLon).putInt(maxLat).putInt(maxLon);
        return buf.array();
    }

    @Test
    void readsBoundingBox() throws IOException {
        Path file = dir.resolve("a.map");
        Files.write(file, mapHeader("mapsforge binary OSM", -16_070_000, -48_305_000, -15_480_000, -47_290_000));
        assertEquals(new BoundingBox(-16.07, -48.305, -15.48, -47.29), MapHeaderReader.readBoundingBox(file));
    }

    @Test
    void rejectsWrongMagic() throws IOException {
        Path file = dir.resolve("b.map");
        Files.write(file, mapHeader("not a mapsforge file", 0, 0, 1, 1));
        assertThrows(InvalidMapFileException.class, () -> MapHeaderReader.readBoundingBox(file));
    }

    @Test
    void rejectsTruncatedFile() throws IOException {
        Path file = dir.resolve("c.map");
        byte[] full = mapHeader("mapsforge binary OSM", 0, 0, 1, 1);
        Files.write(file, java.util.Arrays.copyOf(full, 50));
        assertThrows(InvalidMapFileException.class, () -> MapHeaderReader.readBoundingBox(file));
    }
}
