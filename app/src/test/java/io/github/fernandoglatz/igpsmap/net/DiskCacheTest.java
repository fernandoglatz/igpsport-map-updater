package io.github.fernandoglatz.igpsmap.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskCacheTest {

    @TempDir
    Path dir;

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-18T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void servesFromCacheWithinMaxAgeAndRefetchesAfter() throws IOException {
        MutableClock clock = new MutableClock();
        DiskCache cache = new DiskCache(dir, Duration.ofDays(7), clock);
        AtomicInteger calls = new AtomicInteger();
        String key = "https://download.openstreetmap.fr/polygons/a b:c.poly";

        assertEquals("v1", cache.getOrFetch(key, () -> "v" + calls.incrementAndGet()));
        assertEquals("v1", cache.getOrFetch(key, () -> "v" + calls.incrementAndGet()));
        assertEquals(1, calls.get());

        clock.now = clock.now.plus(Duration.ofDays(8));
        assertEquals("v2", cache.getOrFetch(key, () -> "v" + calls.incrementAndGet()));
    }

    @Test
    void failingFetchStoresNothing() throws IOException {
        DiskCache cache = new DiskCache(dir, Duration.ofDays(7), Clock.systemUTC());
        assertThrows(IOException.class, () -> cache.getOrFetch("k", () -> {
            throw new IOException("boom");
        }));
        assertEquals("ok", cache.getOrFetch("k", () -> "ok"));
    }

    @Test
    void clearForcesRefetch() throws IOException {
        DiskCache cache = new DiskCache(dir, Duration.ofDays(7), Clock.systemUTC());
        cache.getOrFetch("k", () -> "a");
        cache.clear();
        assertEquals("b", cache.getOrFetch("k", () -> "b"));
    }
}
