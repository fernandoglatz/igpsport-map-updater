package io.github.fernandoglatz.igpsmap.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.net.DiskCache;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.StubServer;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OsmBoundarySourceTest {

    private static final URI POLYGONS = URI.create("https://polygons.openstreetmap.fr/");

    /** Two Japanese prefectures as Overpass returns them (out ids tags bb), plus one without bounds. */
    static final String OVERPASS_JSON = """
            {"version":0.6,"elements":[
             {"type":"relation","id":2679957,
              "bounds":{"minlat":34.8976,"minlon":139.7393,"maxlat":36.1043,"maxlon":140.8740},
              "tags":{"admin_level":"4","boundary":"administrative","name":"千葉県","name:en":"Chiba Prefecture"}},
             {"type":"relation","id":3795031,
              "bounds":{"minlat":32.7028,"minlon":132.4800,"maxlat":33.8829,"maxlon":134.3117},
              "tags":{"admin_level":"4","boundary":"administrative","name":"高知県"}},
             {"type":"relation","id":1,"tags":{"admin_level":"6","name":"No bounds"}}
            ]}
            """;

    @Test
    void parsesBoundariesIntoPolygonCandidates() {
        List<PolyCandidate> candidates = OsmBoundarySource.parse(OVERPASS_JSON, POLYGONS);

        assertEquals(2, candidates.size());
        PolyCandidate chiba = candidates.get(0);
        assertEquals("Chiba Prefecture (OSM)", chiba.name());
        assertEquals(URI.create("https://polygons.openstreetmap.fr/get_poly.py?id=2679957&params=0"), chiba.url());
        assertEquals(GeoCode.toTileBox(new BoundingBox(34.8976, 139.7393, 36.1043, 140.8740)), chiba.tileBox());
        assertEquals("高知県 (OSM)", candidates.get(1).name()); // falls back to the local name
    }

    @Test
    void overpassRuntimeErrorIsRejected() {
        String json = "{\"elements\":[],\"remark\":\"runtime error: Query timed out in \\\"query\\\" at line 1 after 121 seconds.\"}";
        assertThrows(IllegalArgumentException.class, () -> OsmBoundarySource.parse(json, POLYGONS));
    }

    @Test
    void queryAsksForAdminLevelsTwoToSixInsideTheCountry() {
        String query = OsmBoundarySource.query("JP");
        assertTrue(query.contains("area[\"ISO3166-1\"=\"JP\"][\"admin_level\"=\"2\"]"), query);
        assertTrue(query.contains("[\"admin_level\"~\"^[2-6]$\"]"), query);
        assertTrue(query.contains("out ids tags bb;"), query);
    }

    @Test
    void fetchesOncePerCountryThenUsesTheCache(@TempDir Path dir) throws IOException {
        List<String> queries = new CopyOnWriteArrayList<>();
        try (StubServer server = new StubServer()) {
            server.handle("/api/interpreter", ex -> {
                queries.add(URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
                StubServer.send(ex, 200, OVERPASS_JSON.getBytes(StandardCharsets.UTF_8));
            });
            HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
            DiskCache cache = new DiskCache(dir, Duration.ofDays(7), Clock.systemUTC());
            OsmBoundarySource source = new OsmBoundarySource(fetcher, cache, server.uri("/api/interpreter"), POLYGONS);

            assertEquals(2, source.boundaries("JP").size());
            assertEquals(2, source.boundaries("JP").size());
            assertEquals(1, queries.size());
            assertTrue(queries.get(0).startsWith("data=[out:json]"), queries.get(0));
        }
    }

    @Test
    void fetchesTheRealPolygonAndRequestsGenerationWhenMissing(@TempDir Path dir) throws IOException {
        java.util.concurrent.atomic.AtomicBoolean generated = new java.util.concurrent.atomic.AtomicBoolean();
        String poly = "p\n1\n140 35\n140.5 35\n140.5 35.5\n140 35\nEND\nEND\n";
        try (StubServer server = new StubServer()) {
            server.handle("/index.py", ex -> {
                generated.set(true);
                StubServer.send(ex, 200, "ok".getBytes(StandardCharsets.UTF_8));
            });
            server.handle("/get_poly.py", ex -> StubServer.send(ex, 200,
                    (generated.get() ? poly : "None").getBytes(StandardCharsets.UTF_8)));
            HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
            OsmBoundarySource source = new OsmBoundarySource(fetcher,
                    new DiskCache(dir, Duration.ofDays(7), Clock.systemUTC()), server.uri("/api/interpreter"), server.uri("/"));
            PolyCandidate candidate = OsmBoundarySource.parse(OVERPASS_JSON, server.uri("/")).get(0);

            assertEquals(0.125, source.geometry(candidate).getArea(), 1e-9);
            assertTrue(generated.get());
        }
    }

    private static OsmBoundarySource retrying(Path dir, List<URI> endpoints) {
        HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
        return new OsmBoundarySource(fetcher, new DiskCache(dir, Duration.ofDays(7), Clock.systemUTC()), endpoints,
                POLYGONS, List.of(Duration.ZERO, Duration.ZERO));
    }

    @Test
    void busyServerIsRetried(@TempDir Path dir) throws IOException {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (StubServer server = new StubServer()) {
            server.handle("/api/interpreter", ex -> {
                if (calls.incrementAndGet() < 3) {
                    StubServer.send(ex, 502, new byte[0]);
                } else {
                    StubServer.send(ex, 200, OVERPASS_JSON.getBytes(StandardCharsets.UTF_8));
                }
            });
            assertEquals(2, retrying(dir, List.of(server.uri("/api/interpreter"))).boundaries("JP").size());
            assertEquals(3, calls.get());
        }
    }

    @Test
    void mirrorIsUsedWhenThePrimaryIsBusy(@TempDir Path dir) throws IOException {
        try (StubServer server = new StubServer()) {
            server.handle("/primary", ex -> StubServer.send(ex, 504, new byte[0]));
            server.handle("/mirror", ex -> StubServer.send(ex, 200, OVERPASS_JSON.getBytes(StandardCharsets.UTF_8)));
            assertEquals(2, retrying(dir, List.of(server.uri("/primary"), server.uri("/mirror"))).boundaries("JP").size());
        }
    }

    @Test
    void givesUpWithAShortMessageWhenEveryAttemptIsBusy(@TempDir Path dir) throws IOException {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (StubServer server = new StubServer()) {
            server.handle("/api/interpreter", ex -> {
                calls.incrementAndGet();
                StubServer.send(ex, 429, new byte[0]);
            });
            IOException e = assertThrows(IOException.class,
                    () -> retrying(dir, List.of(server.uri("/api/interpreter"))).boundaries("JP"));
            assertEquals(3, calls.get()); // first try + two backoffs
            assertTrue(e.getMessage().contains("busy"), e.getMessage());
            assertTrue(!e.getMessage().contains("data="), e.getMessage());
        }
    }

    @Test
    void badRequestIsNotRetried(@TempDir Path dir) throws IOException {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (StubServer server = new StubServer()) {
            server.handle("/api/interpreter", ex -> {
                calls.incrementAndGet();
                StubServer.send(ex, 400, new byte[0]);
            });
            assertThrows(IOException.class, () -> retrying(dir, List.of(server.uri("/api/interpreter"))).boundaries("JP"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void serverErrorIsAnIOException(@TempDir Path dir) throws IOException {
        try (StubServer server = new StubServer()) {
            server.handle("/api/interpreter", ex -> StubServer.send(ex, 429, new byte[0]));
            HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
            OsmBoundarySource source = new OsmBoundarySource(fetcher,
                    new DiskCache(dir, Duration.ofDays(7), Clock.systemUTC()), server.uri("/api/interpreter"), POLYGONS);
            assertThrows(IOException.class, () -> source.boundaries("JP"));
        }
    }
}
