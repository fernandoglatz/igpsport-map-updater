package io.github.fernandoglatz.igpsmap.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.stream.Stream;

/** Text cache on disk, keyed by any string (usually a URL), with a maximum age. */
public final class DiskCache {

    @FunctionalInterface
    public interface IOSupplier<T> {
        T get() throws IOException;
    }

    private final Path dir;
    private final Duration maxAge;
    private final Clock clock;

    public DiskCache(Path dir, Duration maxAge, Clock clock) {
        this.dir = dir;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    public String getOrFetch(String key, IOSupplier<String> fetch) throws IOException {
        Path file = dir.resolve(hash(key));
        if (Files.exists(file)) {
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            if (!modified.plus(maxAge).isBefore(clock.instant())) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        }
        String value = fetch.get();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "entry", ".tmp");
        try {
            Files.writeString(tmp, value, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(tmp, java.nio.file.attribute.FileTime.from(clock.instant()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return value;
    }

    public void clear() throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
