package io.github.fernandoglatz.igpsmap.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpFetcherTest {

    @Test
    void decodesDispositionNamesWithoutTreatingPlusAsSpace() {
        assertEquals("AB+file.map", HttpFetcher.dispositionFileName("attachment; filename*=UTF-8''AB+file%2Emap"));
        assertEquals("fallback.map", HttpFetcher.dispositionFileName("attachment; filename*=UTF-8''%ZZ; filename=\"fallback.map\""));
        assertEquals(null, HttpFetcher.dispositionFileName("attachment"));
    }

    private static final byte[] BODY = new byte[1000];

    static {
        for (int i = 0; i < BODY.length; i++) {
            BODY[i] = (byte) (i % 251);
        }
    }

    @TempDir
    Path dir;

    private StubServer server;
    private HttpFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubServer();
        fetcher = new HttpFetcher(HttpClient.newHttpClient(), "test-agent/1", Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void userAgentCarriesTheReleaseVersion() {
        assertEquals("igpsport-map-updater/1.2.3 (+https://github.com/fernandoglatz/igpsport-map-updater)",
                HttpFetcher.userAgent("1.2.3"));
    }

    @Test
    void userAgentFallsBackToDevWithoutVersion() {
        assertEquals("igpsport-map-updater/dev (+https://github.com/fernandoglatz/igpsport-map-updater)",
                HttpFetcher.userAgent(null));
    }

    @Test
    void getTextReturnsBodyAndSendsUserAgent() throws IOException {
        server.handle("/a", ex -> StubServer.send(ex, 200, "hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals("hello", fetcher.getText(server.uri("/a")));
        assertEquals("test-agent/1", server.requests.get(0).userAgent());
    }

    @Test
    void getTextThrowsWithStatusOnNotFound() {
        server.handle("/missing", ex -> StubServer.send(ex, 404, new byte[0]));
        HttpStatusException e = assertThrows(HttpStatusException.class, () -> fetcher.getText(server.uri("/missing")));
        assertEquals(404, e.status());
    }

    @Test
    void statusErrorMessageLeavesOutTheQueryString() {
        server.handle("/q", ex -> StubServer.send(ex, 502, new byte[0]));
        HttpStatusException e = assertThrows(HttpStatusException.class,
                () -> fetcher.getText(java.net.URI.create(server.uri("/q") + "?data=%5Bvery%20long%5D")));
        assertTrue(e.getMessage().endsWith("/q"), e.getMessage());
    }

    @Test
    void getTextTimesOut() {
        server.handle("/slow", ex -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            StubServer.send(ex, 200, new byte[] {1});
        });
        assertThrows(IOException.class, () -> fetcher.getText(server.uri("/slow")));
    }

    @Test
    void withTimeoutAllowsSlowerResponses() throws IOException {
        server.handle("/slowish", ex -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            StubServer.send(ex, 200, "ok".getBytes(StandardCharsets.UTF_8));
        });
        HttpFetcher impatient = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofMillis(300));
        assertThrows(IOException.class, () -> impatient.getText(server.uri("/slowish")));
        assertEquals("ok", impatient.withTimeout(Duration.ofSeconds(5)).getText(server.uri("/slowish")));
    }

    @Test
    void downloadResumesFromPartFile() throws IOException {
        server.handle("/f.pbf", ex -> {
            ex.getResponseHeaders().add("ETag", "\"same\"");
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=") && "\"same\"".equals(ex.getRequestHeaders().getFirst("If-Range"))) {
                int from = Integer.parseInt(range.substring(6, range.indexOf('-')));
                byte[] rest = Arrays.copyOfRange(BODY, from, BODY.length);
                ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (BODY.length - 1) + "/" + BODY.length);
                StubServer.send(ex, 206, rest);
            } else {
                StubServer.send(ex, 200, BODY);
            }
        });
        Path target = dir.resolve("f.pbf");
        Files.write(dir.resolve("f.pbf.part"), Arrays.copyOf(BODY, 100));
        Files.writeString(dir.resolve("f.pbf.part.validator"), "\"same\"");
        AtomicLong lastDone = new AtomicLong();
        AtomicLong lastTotal = new AtomicLong();

        fetcher.download(server.uri("/f.pbf"), target, (done, total) -> {
            lastDone.set(done);
            lastTotal.set(total);
        });

        assertArrayEquals(BODY, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("f.pbf.part")));
        assertEquals("bytes=100-", server.requests.get(0).range());
        assertEquals(1000, lastDone.get());
        assertEquals(1000, lastTotal.get());
    }

    @Test
    void resumeSendsIfRangeWithValidatorOfThePartialDownload() throws IOException {
        server.handle("/v.pbf", ex -> {
            ex.getResponseHeaders().add("ETag", "\"v2\"");
            String ifRange = ex.getRequestHeaders().getFirst("If-Range");
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null && "\"v2\"".equals(ifRange)) {
                int from = Integer.parseInt(range.substring(6, range.indexOf('-')));
                StubServer.send(ex, 206, Arrays.copyOfRange(BODY, from, BODY.length));
            } else {
                StubServer.send(ex, 200, BODY); // file changed (or no validator): full body
            }
        });
        Path target = dir.resolve("v.pbf");
        // A partial download of an older version of the file.
        Files.write(dir.resolve("v.pbf.part"), new byte[] {7, 7, 7, 7});
        Files.writeString(dir.resolve("v.pbf.part.validator"), "\"v1\"");

        fetcher.download(server.uri("/v.pbf"), target, (d, t) -> { });

        assertArrayEquals(BODY, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("v.pbf.part.validator")));
    }

    @Test
    void partWithoutValidatorIsNotResumed() throws IOException {
        server.handle("/n.pbf", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null) {
                StubServer.send(ex, 206, Arrays.copyOfRange(BODY, 4, BODY.length));
            } else {
                StubServer.send(ex, 200, BODY);
            }
        });
        Path target = dir.resolve("n.pbf");
        Files.write(dir.resolve("n.pbf.part"), new byte[] {7, 7, 7, 7});
        fetcher.download(server.uri("/n.pbf"), target, (d, t) -> { });
        assertArrayEquals(BODY, Files.readAllBytes(target));
    }

    @Test
    void downloadRestartsWhenServerIgnoresRange() throws IOException {
        server.handle("/f.pbf", ex -> StubServer.send(ex, 200, BODY));
        Path target = dir.resolve("f.pbf");
        Files.write(dir.resolve("f.pbf.part"), new byte[] {9, 9, 9});
        fetcher.download(server.uri("/f.pbf"), target, (d, t) -> { });
        assertArrayEquals(BODY, Files.readAllBytes(target));
    }

    @Test
    void failedDownloadKeepsNoTargetFile() {
        server.handle("/gone.pbf", ex -> StubServer.send(ex, 500, new byte[0]));
        Path target = dir.resolve("gone.pbf");
        assertThrows(HttpStatusException.class, () -> fetcher.download(server.uri("/gone.pbf"), target, (d, t) -> { }));
        assertFalse(Files.exists(target));
    }

    @Test
    void lastModifiedReadsHeader() {
        Instant when = Instant.parse("2026-11-01T10:00:00Z");
        server.handle("/lm", ex -> {
            ex.getResponseHeaders().add("Last-Modified",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(when.atOffset(ZoneOffset.UTC)));
            StubServer.send(ex, 200, new byte[0]);
        });
        assertEquals(when, fetcher.lastModified(server.uri("/lm")).orElseThrow());
        assertEquals("HEAD", server.requests.get(0).method());
    }

    @Test
    void lastModifiedEmptyOnError() {
        server.handle("/x", ex -> StubServer.send(ex, 500, new byte[0]));
        assertTrue(fetcher.lastModified(server.uri("/x")).isEmpty());
    }

    @Test
    void readRangeReturnsTheSuffixAndTheTotalSize() throws IOException {
        server.handle("/z", ex -> StubServer.sendRange(ex, BODY));
        HttpFetcher.RangeResult r = fetcher.readRange(server.uri("/z"), "-100");
        assertTrue(r.partial());
        assertEquals(1000, r.totalSize());
        assertArrayEquals(Arrays.copyOfRange(BODY, 900, 1000), r.body());
        assertEquals("bytes=-100", server.requests.get(0).range());
    }

    @Test
    void readRangeReturnsAnExplicitSlice() throws IOException {
        server.handle("/z", ex -> StubServer.sendRange(ex, BODY));
        HttpFetcher.RangeResult r = fetcher.readRange(server.uri("/z"), "10-19");
        assertArrayEquals(Arrays.copyOfRange(BODY, 10, 20), r.body());
        assertEquals(1000, r.totalSize());
    }

    @Test
    void readRangeReportsServersThatIgnoreRanges() throws IOException {
        server.handle("/z", ex -> StubServer.send(ex, 200, BODY));
        HttpFetcher.RangeResult r = fetcher.readRange(server.uri("/z"), "-100");
        assertFalse(r.partial());
        assertEquals(1000, r.totalSize());
        assertArrayEquals(BODY, r.body());
    }

    @Test
    void readRangeFailsOnHttpErrors() {
        server.handle("/z", ex -> StubServer.send(ex, 404, new byte[0]));
        assertThrows(HttpStatusException.class, () -> fetcher.readRange(server.uri("/z"), "-100"));
    }

    @Test
    void openRangeStreamsTheSlice() throws IOException {
        server.handle("/z", ex -> StubServer.sendRange(ex, BODY));
        try (java.io.InputStream in = fetcher.openRange(server.uri("/z"), 500, 599)) {
            assertArrayEquals(Arrays.copyOfRange(BODY, 500, 600), in.readAllBytes());
        }
        assertEquals("bytes=500-599", server.requests.get(0).range());
    }

    @Test
    void openRangeRejectsAFullResponse() {
        server.handle("/z", ex -> StubServer.send(ex, 200, BODY));
        assertThrows(IOException.class, () -> fetcher.openRange(server.uri("/z"), 500, 599).close());
    }
}
