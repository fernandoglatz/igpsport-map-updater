package io.github.fernandoglatz.igpsmap.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PbfDateReaderTest {

    @TempDir
    Path dir;

    private static final long TIMESTAMP_2023_01_10 = 1_673_308_800L;

    @Test
    void readsReplicationTimestampFromZlibBlob() throws IOException {
        Path pbf = write("z.osm.pbf", pbf(headerBlock(TIMESTAMP_2023_01_10), true));
        assertEquals(Optional.of(LocalDate.of(2023, 1, 10)), PbfDateReader.readReplicationDate(pbf));
    }

    @Test
    void readsReplicationTimestampFromRawBlob() throws IOException {
        Path pbf = write("r.osm.pbf", pbf(headerBlock(TIMESTAMP_2023_01_10), false));
        assertEquals(Optional.of(LocalDate.of(2023, 1, 10)), PbfDateReader.readReplicationDate(pbf));
    }

    @Test
    void emptyWhenHeaderHasNoTimestamp() throws IOException {
        Path pbf = write("n.osm.pbf", pbf(headerBlock(null), true));
        assertTrue(PbfDateReader.readReplicationDate(pbf).isEmpty());
    }

    @Test
    void emptyForGarbage() throws IOException {
        Path pbf = write("g.osm.pbf", "this is not a pbf file at all".getBytes(StandardCharsets.US_ASCII));
        assertTrue(PbfDateReader.readReplicationDate(pbf).isEmpty());
    }

    @Test
    void emptyForMissingFile() {
        assertTrue(PbfDateReader.readReplicationDate(dir.resolve("missing.pbf")).isEmpty());
    }

    @Test
    void resolveDateFallsBackToModificationTime() throws IOException {
        Path pbf = write("m.osm.pbf", new byte[] {1, 2, 3});
        Files.setLastModifiedTime(pbf, FileTime.from(Instant.parse("2026-01-12T10:00:00Z")));
        assertEquals(LocalDate.of(2026, 1, 12), PbfDateReader.resolveDate(pbf));
    }

    @Test
    void formatsAsYyMmDd() {
        assertEquals("230110", PbfDateReader.format(LocalDate.of(2023, 1, 10)));
    }

    private Path write(String name, byte[] data) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, data);
        return file;
    }

    // --- minimal protobuf encoding helpers ---

    static byte[] headerBlock(Long timestamp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        lengthDelimited(out, 4, "OsmSchema-V0.6".getBytes(StandardCharsets.US_ASCII)); // required_features
        lengthDelimited(out, 16, "osmium/1.16".getBytes(StandardCharsets.US_ASCII)); // writingprogram
        if (timestamp != null) {
            varint(out, (32L << 3)); // field 32, wire type 0
            varint(out, timestamp);
        }
        return out.toByteArray();
    }

    static byte[] pbf(byte[] headerBlock, boolean zlib) {
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        if (zlib) {
            varint(blob, (2L << 3)); // raw_size
            varint(blob, headerBlock.length);
            lengthDelimited(blob, 3, deflate(headerBlock));
        } else {
            lengthDelimited(blob, 1, headerBlock);
        }
        byte[] blobBytes = blob.toByteArray();

        ByteArrayOutputStream blobHeader = new ByteArrayOutputStream();
        lengthDelimited(blobHeader, 1, "OSMHeader".getBytes(StandardCharsets.US_ASCII));
        varint(blobHeader, (3L << 3));
        varint(blobHeader, blobBytes.length);
        byte[] headerBytes = blobHeader.toByteArray();

        ByteBuffer file = ByteBuffer.allocate(4 + headerBytes.length + blobBytes.length);
        file.putInt(headerBytes.length).put(headerBytes).put(blobBytes);
        return file.array();
    }

    private static void lengthDelimited(ByteArrayOutputStream out, int field, byte[] data) {
        varint(out, ((long) field << 3) | 2);
        varint(out, data.length);
        out.writeBytes(data);
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] buf = new byte[data.length + 64];
        int n = deflater.deflate(buf);
        deflater.end();
        return java.util.Arrays.copyOf(buf, n);
    }
}
