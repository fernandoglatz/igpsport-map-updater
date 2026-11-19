package io.github.fernandoglatz.igpsmap.io;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads the {@code osmosis_replication_timestamp} from the HeaderBlock of an OSM PBF file.
 * Minimal protobuf walk: only the first blob is read.
 */
public final class PbfDateReader {

    private static final DateTimeFormatter YY_MM_DD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final int MAX_BLOB_HEADER = 64 * 1024;
    private static final int MAX_BLOB = 32 * 1024 * 1024;
    private static final int FIELD_REPLICATION_TIMESTAMP = 32;

    private PbfDateReader() {
    }

    public static Optional<LocalDate> readReplicationDate(Path pbf) {
        try (InputStream raw = Files.newInputStream(pbf);
             DataInputStream in = new DataInputStream(raw)) {
            int headerLength = in.readInt();
            if (headerLength <= 0 || headerLength > MAX_BLOB_HEADER) {
                return Optional.empty();
            }
            byte[] blobHeader = in.readNBytes(headerLength);
            long dataSize = findVarint(blobHeader, 3);
            if (dataSize <= 0 || dataSize > MAX_BLOB) {
                return Optional.empty();
            }
            byte[] blob = in.readNBytes((int) dataSize);
            byte[] headerBlock = unpackBlob(blob);
            if (headerBlock == null) {
                return Optional.empty();
            }
            long seconds = findVarint(headerBlock, FIELD_REPLICATION_TIMESTAMP);
            if (seconds <= 0) {
                return Optional.empty();
            }
            return Optional.of(LocalDate.ofInstant(Instant.ofEpochSecond(seconds), ZoneOffset.UTC));
        } catch (IOException | RuntimeException | DataFormatException e) {
            return Optional.empty();
        }
    }

    /** Replication date if present, otherwise the file's modification date (UTC). */
    public static LocalDate resolveDate(Path pbf) {
        return readReplicationDate(pbf).orElseGet(() -> {
            try {
                return LocalDate.ofInstant(Files.getLastModifiedTime(pbf).toInstant(), ZoneOffset.UTC);
            } catch (IOException e) {
                return LocalDate.now(ZoneOffset.UTC);
            }
        });
    }

    public static String format(LocalDate date) {
        return date.format(YY_MM_DD);
    }

    private static byte[] unpackBlob(byte[] blob) throws DataFormatException {
        byte[] raw = findBytes(blob, 1);
        if (raw != null) {
            return raw;
        }
        byte[] zlib = findBytes(blob, 3);
        if (zlib == null) {
            return null;
        }
        long rawSize = findVarint(blob, 2);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(zlib);
            byte[] out = new byte[rawSize > 0 && rawSize <= MAX_BLOB ? (int) rawSize : 1024 * 1024];
            int n = inflater.inflate(out);
            return java.util.Arrays.copyOf(out, n);
        } finally {
            inflater.end();
        }
    }

    /** Returns the first varint value of {@code field}, or -1. */
    private static long findVarint(byte[] message, int field) {
        Cursor c = new Cursor(message);
        while (c.pos < message.length) {
            long key = c.varint();
            int f = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == 0) {
                long value = c.varint();
                if (f == field) {
                    return value;
                }
            } else if (!c.skip(wire)) {
                return -1;
            }
        }
        return -1;
    }

    /** Returns the first length-delimited value of {@code field}, or null. */
    private static byte[] findBytes(byte[] message, int field) {
        Cursor c = new Cursor(message);
        while (c.pos < message.length) {
            long key = c.varint();
            int f = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == 2) {
                int length = (int) c.varint();
                if (length < 0 || c.pos + length > message.length) {
                    return null;
                }
                if (f == field) {
                    return java.util.Arrays.copyOfRange(message, c.pos, c.pos + length);
                }
                c.pos += length;
            } else if (wire == 0) {
                c.varint();
            } else if (!c.skip(wire)) {
                return null;
            }
        }
        return null;
    }

    private static final class Cursor {
        private final byte[] data;
        private int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        long varint() {
            long result = 0;
            int shift = 0;
            while (pos < data.length && shift < 64) {
                byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            throw new IllegalStateException("Truncated varint");
        }

        boolean skip(int wire) {
            switch (wire) {
                case 1 -> pos += 8;
                case 2 -> {
                    int length = (int) varint();
                    pos += length;
                }
                case 5 -> pos += 4;
                default -> {
                    return false;
                }
            }
            return pos <= data.length;
        }
    }
}
