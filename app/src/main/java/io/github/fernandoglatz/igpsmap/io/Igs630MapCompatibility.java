package io.github.fernandoglatz.igpsmap.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Keeps generated map metadata consistent with the conservative iGS630 format. */
public final class Igs630MapCompatibility {
    private static final byte[] MAGIC = "mapsforge binary OSM".getBytes(StandardCharsets.US_ASCII);
    // Observed in official V1 iGS630 maps. V3 templates and generated sources
    // must not supply their newer writer metadata to this compatibility profile.
    private static final byte[] DEFAULT_CREATOR = "mapsforge-map-writer-0.16.Q".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_HEADER_SIZE = 1024 * 1024;

    private Igs630MapCompatibility() { }

    public static byte[] readCreatedBy(Path map) throws IOException {
        return header(map).creator().clone();
    }

    public static void patch(Path original, Path generated) throws IOException {
        if (Files.isSameFile(original, generated)) {
            throw new IOException("Original and generated map must be different files");
        }
        byte[] source = header(original).creator();
        byte[] creator = source.length > 0 && source[source.length - 1] == 'Q' ? source : DEFAULT_CREATOR;
        Header old = header(generated);
        ByteArrayOutputStream replacement = new ByteArrayOutputStream();
        writeUnsigned(replacement, creator.length);
        replacement.write(creator);
        byte[] field = replacement.toByteArray();
        int delta = field.length - (old.creatorEnd() - old.creatorStart());
        ByteArrayOutputStream updated = new ByteArrayOutputStream(old.bytes().length + delta);
        updated.write(old.bytes(), 0, old.creatorStart());
        updated.write(field);
        updated.write(old.bytes(), old.creatorEnd(), old.bytes().length - old.creatorEnd());
        byte[] bytes = updated.toByteArray();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putInt(20, bytes.length - 24);
        buffer.putLong(28, Files.size(generated) + delta);
        bytes[old.flagsOffset()] |= 0x04;
        for (int offset : old.subfileOffsets()) {
            long start = ByteBuffer.wrap(old.bytes()).getLong(offset);
            buffer.putLong(offset + delta, start + delta);
        }

        Path temporary = Files.createTempFile(generated.toAbsolutePath().getParent(), ".igs630-header-", ".tmp");
        try {
            try (InputStream in = Files.newInputStream(generated); OutputStream out = Files.newOutputStream(temporary)) {
                out.write(bytes);
                in.skipNBytes(old.bytes().length);
                in.transferTo(out);
            }
            try {
                Files.move(temporary, generated, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, generated, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Header header(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] prefix = in.readNBytes(24);
            if (prefix.length != 24 || !Arrays.equals(Arrays.copyOf(prefix, 20), MAGIC)) {
                throw new IOException("Not a Mapsforge map: " + file);
            }
            int remaining = ByteBuffer.wrap(prefix).getInt(20);
            if (remaining < 39 || remaining > MAX_HEADER_SIZE) {
                throw new IOException("Invalid map header size: " + file);
            }
            byte[] rest = in.readNBytes(remaining);
            if (rest.length != remaining) {
                throw new IOException("Truncated map header: " + file);
            }
            byte[] bytes = Arrays.copyOf(prefix, 24 + remaining);
            System.arraycopy(rest, 0, bytes, 24, remaining);
            ByteBuffer fixed = ByteBuffer.wrap(bytes);
            if (fixed.getInt(24) != 3 || fixed.getLong(28) != Files.size(file)) {
                throw new IOException("Expected a complete Mapsforge version 3 map: " + file);
            }
            Cursor cursor = new Cursor(bytes, 62);
            cursor.string(); // projection
            int flagsOffset = cursor.position;
            int flags = cursor.byteValue();
            if ((flags & 0x40) != 0) cursor.skip(8);
            if ((flags & 0x20) != 0) cursor.skip(1);
            if ((flags & 0x10) != 0) cursor.string();
            if ((flags & 0x08) != 0) cursor.string();
            int start = cursor.position;
            byte[] creator = (flags & 0x04) != 0 ? cursor.string() : new byte[0];
            int end = cursor.position;
            for (int table = 0; table < 2; table++) {
                int count = cursor.shortValue();
                for (int tag = 0; tag < count; tag++) cursor.string();
            }
            List<Integer> offsets = new ArrayList<>();
            int intervals = cursor.byteValue();
            for (int interval = 0; interval < intervals; interval++) {
                cursor.skip(3);
                offsets.add(cursor.position);
                long subfileStart = cursor.longValue();
                long size = cursor.longValue();
                if (subfileStart < bytes.length || size < 0 || subfileStart > Files.size(file) - size) {
                    throw new IOException("Invalid map subfile extent: " + file);
                }
            }
            if (intervals == 0 || cursor.position != bytes.length) {
                throw new IOException("Invalid map header layout: " + file);
            }
            return new Header(bytes, flagsOffset, start, end, creator, offsets);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IOException("Invalid Mapsforge header: " + file, e);
        }
    }

    private static void writeUnsigned(OutputStream out, int value) throws IOException {
        while (value >= 128) {
            out.write((value & 127) | 128);
            value >>>= 7;
        }
        out.write(value);
    }

    private record Header(byte[] bytes, int flagsOffset, int creatorStart, int creatorEnd,
                          byte[] creator, List<Integer> subfileOffsets) { }

    private static final class Cursor {
        private final byte[] bytes;
        private int position;
        Cursor(byte[] bytes, int position) { this.bytes = bytes; this.position = position; }
        int byteValue() { return bytes[position++] & 255; }
        int shortValue() { return (byteValue() << 8) | byteValue(); }
        long longValue() {
            long value = ByteBuffer.wrap(bytes).getLong(position);
            skip(8);
            return value;
        }
        void skip(int length) {
            if (length < 0 || position > bytes.length - length) throw new IndexOutOfBoundsException();
            position += length;
        }
        byte[] string() {
            int length = 0;
            for (int shift = 0; shift <= 21; shift += 7) {
                int b = byteValue();
                length |= (b & 127) << shift;
                if ((b & 128) == 0) {
                    int start = position;
                    skip(length);
                    return Arrays.copyOfRange(bytes, start, position);
                }
            }
            throw new IllegalArgumentException("Oversized string");
        }
    }
}
