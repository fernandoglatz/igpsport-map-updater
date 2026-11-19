package io.github.fernandoglatz.igpsmap.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads single entries of a ZIP file on an HTTP server with byte-range requests, so only the
 * central directory and the wanted entries are downloaded (not e.g. routing or elevation data).
 */
public final class RemoteZip {

    /** The content is not a ZIP file (no end-of-central-directory record). */
    public static final class NotAZipException extends IOException {
        public NotAZipException(String message) {
            super(message);
        }
    }

    /** The server answered a range request with the whole file; download it completely instead. */
    public static final class RangeNotSupportedException extends IOException {
        public RangeNotSupportedException(String message) {
            super(message);
        }
    }

    public record Entry(String name, int method, long compressedSize, long size, long crc, long localHeaderOffset) {
        public boolean directory() {
            return name.endsWith("/");
        }
    }

    private static final int EOCD_SIG = 0x06054b50;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    private static final int CENTRAL_SIG = 0x02014b50;
    private static final int LOCAL_SIG = 0x04034b50;
    private static final int EOCD_SIZE = 22;
    /** End record plus the longest possible archive comment. */
    private static final int MAX_TAIL = EOCD_SIZE + 0xFFFF;
    private static final long U32 = 0xFFFFFFFFL;
    private static final int U16 = 0xFFFF;
    private static final Charset CP437 = cp437();

    private final HttpFetcher fetcher;
    private final URI uri;
    private final List<Entry> entries;

    private RemoteZip(HttpFetcher fetcher, URI uri, List<Entry> entries) {
        this.fetcher = fetcher;
        this.uri = uri;
        this.entries = List.copyOf(entries);
    }

    public static RemoteZip open(HttpFetcher fetcher, URI uri) throws IOException {
        HttpFetcher.RangeResult tail = fetcher.readRange(uri, "-" + MAX_TAIL);
        if (!tail.partial()) {
            throw new RangeNotSupportedException("Server ignored the byte range request for " + uri);
        }
        byte[] tailBytes = tail.body();
        long tailStart = tail.totalSize() - tailBytes.length;
        ByteBuffer t = le(tailBytes);

        int eocd = findEocd(tailBytes);
        if (eocd < 0) {
            throw new NotAZipException("Not a ZIP file: " + uri);
        }
        long count = t.getShort(eocd + 10) & U16;
        long cdSize = t.getInt(eocd + 12) & U32;
        long cdOffset = t.getInt(eocd + 16) & U32;
        if (count == U16 || cdSize == U32 || cdOffset == U32) {
            int locator = eocd - 20;
            if (locator < 0 || t.getInt(locator) != ZIP64_LOCATOR_SIG) {
                throw new IOException("ZIP64 locator missing in " + uri);
            }
            long recordOffset = t.getLong(locator + 8);
            ByteBuffer record = le(slice(fetcher, uri, tailBytes, tailStart, recordOffset, 56));
            if (record.getInt(0) != ZIP64_EOCD_SIG) {
                throw new IOException("ZIP64 end record missing in " + uri);
            }
            count = record.getLong(32);
            cdSize = record.getLong(40);
            cdOffset = record.getLong(48);
        }
        if (cdSize > Integer.MAX_VALUE - 8 || cdOffset + cdSize > tail.totalSize()) {
            throw new IOException("Central directory out of range in " + uri);
        }
        byte[] cd = slice(fetcher, uri, tailBytes, tailStart, cdOffset, (int) cdSize);
        return new RemoteZip(fetcher, uri, parseCentralDirectory(cd, count, uri));
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * Downloads and unpacks {@code entry} into {@code target} (written as {@code target.part}, then moved).
     * The listener receives uncompressed bytes written and may throw to cancel; nothing is left behind
     * on failure.
     */
    public void extract(Entry entry, Path target, ProgressListener listener) throws IOException {
        if (entry.method() != ZipEntry.STORED && entry.method() != ZipEntry.DEFLATED) {
            throw new IOException("Unsupported compression method " + entry.method() + " for " + entry.name());
        }
        HttpFetcher.RangeResult header = fetcher.readRange(uri,
                entry.localHeaderOffset() + "-" + (entry.localHeaderOffset() + 29));
        ByteBuffer h = le(header.body());
        if (!header.partial() || header.body().length < 30 || h.getInt(0) != LOCAL_SIG) {
            throw new IOException("Bad local header for " + entry.name() + " in " + uri);
        }
        long dataStart = entry.localHeaderOffset() + 30 + (h.getShort(26) & U16) + (h.getShort(28) & U16);

        Files.createDirectories(target.toAbsolutePath().getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            InputStream raw = entry.compressedSize() == 0 ? InputStream.nullInputStream()
                    : fetcher.openRange(uri, dataStart, dataStart + entry.compressedSize() - 1);
            copyEntry(entry, raw, part, listener);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }

    /**
     * Fallback for servers without range support: extracts the entries of a downloaded ZIP whose base
     * name {@code wanted} accepts into {@code dir} (flat, by base name), in archive order.
     */
    public static List<Path> extractLocally(Path zipFile, Predicate<String> wanted, Path dir) throws IOException {
        List<Path> extracted = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = baseName(entry.getName());
                // "." / ".." would resolve to the folder itself or its parent.
                if (entry.isDirectory() || name.isEmpty() || name.equals(".") || name.equals("..")
                        || !wanted.test(name)) {
                    continue;
                }
                Files.createDirectories(dir);
                Path target = dir.resolve(name);
                Path part = dir.resolve(name + ".part");
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(part);
                }
                extracted.add(target);
            }
        }
        return extracted;
    }

    /** The file name after the last '/' or '\'; empty for directory entries. */
    public static String baseName(String entryName) {
        int cut = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
        return entryName.substring(cut + 1);
    }

    private static void copyEntry(Entry entry, InputStream raw, Path part, ProgressListener listener) throws IOException {
        Inflater inflater = entry.method() == ZipEntry.DEFLATED ? new Inflater(true) : null;
        // A raw ("nowrap") inflater may need one extra byte after the data to finish, as ZipFile does.
        try (InputStream in = inflater == null ? raw
                : new InflaterInputStream(new SequenceInputStream(raw, new ByteArrayInputStream(new byte[1])), inflater, 64 * 1024);
             OutputStream out = Files.newOutputStream(part)) {
            CRC32 crc = new CRC32();
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            listener.onProgress(0, entry.size());
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                crc.update(buffer, 0, n);
                done += n;
                if (done > entry.size()) {
                    throw new IOException("Entry " + entry.name() + " is larger than declared");
                }
                listener.onProgress(done, entry.size());
            }
            if (done != entry.size()) {
                throw new IOException("Entry " + entry.name() + " is truncated (" + done + " of " + entry.size() + " bytes)");
            }
            if (crc.getValue() != entry.crc()) {
                throw new IOException("CRC mismatch for " + entry.name());
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("Corrupt data in " + entry.name() + " (CRC check not reached): " + e.getMessage(), e);
        } finally {
            if (inflater != null) {
                inflater.end();
            }
        }
    }

    private static List<Entry> parseCentralDirectory(byte[] cd, long count, URI uri) throws IOException {
        ByteBuffer b = le(cd);
        List<Entry> result = new ArrayList<>();
        int p = 0;
        for (long i = 0; i < count; i++) {
            if (p + 46 > cd.length || b.getInt(p) != CENTRAL_SIG) {
                throw new IOException("Corrupt central directory in " + uri);
            }
            int flags = b.getShort(p + 8) & U16;
            int method = b.getShort(p + 10) & U16;
            long crc = b.getInt(p + 16) & U32;
            long compressed = b.getInt(p + 20) & U32;
            long size = b.getInt(p + 24) & U32;
            int nameLen = b.getShort(p + 28) & U16;
            int extraLen = b.getShort(p + 30) & U16;
            int commentLen = b.getShort(p + 32) & U16;
            long offset = b.getInt(p + 42) & U32;
            if (p + 46 + nameLen + extraLen + commentLen > cd.length) {
                throw new IOException("Corrupt central directory in " + uri);
            }
            String name = new String(cd, p + 46, nameLen, (flags & 0x800) != 0 ? StandardCharsets.UTF_8 : CP437);
            // ZIP64 extra field: the 64-bit values follow in this order, only for fields set to 0xFFFFFFFF.
            int e = p + 46 + nameLen;
            int extraEnd = e + extraLen;
            while (e + 4 <= extraEnd) {
                int id = b.getShort(e) & U16;
                int len = b.getShort(e + 2) & U16;
                if (id == 0x0001) {
                    int q = e + 4;
                    if (size == U32 && q + 8 <= e + 4 + len) {
                        size = b.getLong(q);
                        q += 8;
                    }
                    if (compressed == U32 && q + 8 <= e + 4 + len) {
                        compressed = b.getLong(q);
                        q += 8;
                    }
                    if (offset == U32 && q + 8 <= e + 4 + len) {
                        offset = b.getLong(q);
                    }
                }
                e += 4 + len;
            }
            result.add(new Entry(name, method, compressed, size, crc, offset));
            p += 46 + nameLen + extraLen + commentLen;
        }
        return result;
    }

    /** Last EOCD signature whose comment length reaches exactly to the end of the data. */
    private static int findEocd(byte[] tail) {
        ByteBuffer b = le(tail);
        for (int i = tail.length - EOCD_SIZE; i >= 0; i--) {
            if (b.getInt(i) == EOCD_SIG && i + EOCD_SIZE + (b.getShort(i + 20) & U16) == tail.length) {
                return i;
            }
        }
        return -1;
    }

    /** Bytes {@code [offset, offset+length)} of the file, from the tail already read or a new range request. */
    private static byte[] slice(HttpFetcher fetcher, URI uri, byte[] tail, long tailStart, long offset, int length)
            throws IOException {
        if (offset >= tailStart && offset + length <= tailStart + tail.length) {
            int from = (int) (offset - tailStart);
            return java.util.Arrays.copyOfRange(tail, from, from + length);
        }
        if (length == 0) {
            return new byte[0];
        }
        HttpFetcher.RangeResult r = fetcher.readRange(uri, offset + "-" + (offset + length - 1));
        if (!r.partial() || r.body().length != length) {
            throw new RangeNotSupportedException("Could not read bytes " + offset + "+" + length + " of " + uri);
        }
        return r.body();
    }

    private static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static Charset cp437() {
        try {
            return Charset.forName("IBM437");
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
