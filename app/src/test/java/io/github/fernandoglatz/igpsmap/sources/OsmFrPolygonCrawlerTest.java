package io.github.fernandoglatz.igpsmap.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.net.DiskCache;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.StubServer;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OsmFrPolygonCrawlerTest {

    @TempDir
    Path dir;

    private StubServer server;
    private final Map<String, String> site = new HashMap<>();

    private static String listing(String... hrefs) {
        StringBuilder sb = new StringBuilder("<html><body><h1>Index</h1><pre><a href=\"?C=N;O=D\">Name</a> <a href=\"../\">../</a>\n");
        for (String h : hrefs) {
            sb.append("<a href=\"").append(h).append("\">").append(h).append("</a>   06-Feb-2024 10:00   1234\n");
        }
        return sb.append("</pre></body></html>").toString();
    }

    private static String square(double lon, double lat, double size) {
        return "p\n1\n" + lon + " " + lat + "\n" + (lon + size) + " " + lat + "\n" + (lon + size) + " " + (lat + size)
                + "\n" + lon + " " + (lat + size) + "\n" + lon + " " + lat + "\nEND\nEND\n";
    }

    @BeforeEach
    void setUp() throws IOException {
        site.put("/polygons/", listing("africa/", "south-america/", "south-america.poly", "http://elsewhere/x/"));
        site.put("/polygons/africa/", listing("egypt/"));
        site.put("/polygons/south-america/", listing("brasil/", "brasil.poly"));
        site.put("/polygons/south-america/brasil.poly", square(-74, -34, 39));
        site.put("/polygons/south-america/brasil/", listing("central-west/", "central-west.poly", "broken/"));
        site.put("/polygons/south-america/brasil/central-west.poly", square(-61, -24, 16));
        site.put("/polygons/south-america/brasil/central-west/",
                listing("distrito-federal.poly", "goias.poly", "bad.poly", "deep/"));
        site.put("/polygons/south-america/brasil/central-west/distrito-federal.poly", square(-48.3, -16.05, 0.5));
        site.put("/polygons/south-america/brasil/central-west/goias.poly", square(-53, -19, 6));
        site.put("/polygons/south-america/brasil/central-west/bad.poly", "<html>not a poly</html>");
        site.put("/polygons/south-america/brasil/central-west/deep/", listing("too-deep.poly"));
        site.put("/polygons/south-america/brasil/central-west/deep/too-deep.poly", square(-48, -16, 0.1));

        server = new StubServer();
        server.handle("/polygons/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/broken/")) {
                StubServer.send(ex, 500, new byte[0]);
                return;
            }
            String body = site.get(path);
            StubServer.send(ex, body == null ? 404 : 200, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        });
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private OsmFrPolygonCrawler crawler(int maxDepth) {
        HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
        return new OsmFrPolygonCrawler(fetcher, new DiskCache(dir, Duration.ofDays(7), Clock.systemUTC()),
                server.uri("/polygons/"), maxDepth, 4);
    }

    @Test
    void crawlsCountryPolyAndSubfoldersWithinDepth() throws IOException {
        List<PolyCandidate> found = crawler(1).crawl("south-america/brasil");
        List<String> names = found.stream().map(PolyCandidate::name).sorted().toList();
        assertEquals(List.of("brasil", "central-west", "distrito-federal", "goias"), names);
        PolyCandidate df = found.stream().filter(c -> c.name().equals("distrito-federal")).findFirst().orElseThrow();
        assertEquals(server.uri("/polygons/south-america/brasil/central-west/distrito-federal.poly"), df.url());
        assertEquals(GeoCode.toTileBox(io.github.fernandoglatz.igpsmap.io.PolyFile.boundingBox(df.geometry())), df.tileBox());
    }

    @Test
    void deeperLevelsAreReachedWhenAllowed() throws IOException {
        assertTrue(crawler(2).crawl("south-america/brasil").stream().anyMatch(c -> c.name().equals("too-deep")));
    }

    @Test
    void secondCrawlIsServedFromCache() throws IOException {
        crawler(1).crawl("south-america/brasil");
        long requests = successfulRequests();
        crawler(1).crawl("south-america/brasil");
        assertEquals(requests, successfulRequests());
    }

    /** Failed responses (the 500 folder) are not cached, so they are excluded from the count. */
    private long successfulRequests() {
        return server.requests.stream().filter(r -> !r.path().endsWith("/broken/")).count();
    }

    @Test
    void missingCountryFolderThrows() {
        assertThrows(IOException.class, () -> crawler(1).crawl("south-america/atlantis"));
    }

    @Test
    void findsCountryPathDirectlyOrByIdUnderAnyContinent() throws IOException {
        OsmFrPolygonCrawler crawler = crawler(1);
        assertEquals("south-america/brasil", crawler.findCountryPath("south-america/brasil", "brasil", "Brasil").orElseThrow());
        assertEquals("south-america/brasil", crawler.findCountryPath("latin-america/brazil", "brazil", "Brasil").orElseThrow());
        assertTrue(crawler.findCountryPath("europe/atlantis", "atlantis", "Atlantis").isEmpty());
    }

    @Test
    void findsCountryPathWithUnderscoreVariant() throws IOException {
        site.put("/polygons/", listing("europe/"));
        site.put("/polygons/europe/", listing("united_kingdom/", "united_kingdom.poly"));
        assertEquals("europe/united_kingdom",
                crawler(1).findCountryPath("europe/united-kingdom", "united-kingdom", "United Kingdom").orElseThrow());
    }

    @Test
    void countryThatIsOnlyAPolyFileIsFoundAndCrawled() throws IOException {
        site.put("/polygons/", listing("europe/"));
        site.put("/polygons/europe/", listing("andorra.poly", "france/"));
        site.put("/polygons/europe/andorra.poly", square(1.4, 42.4, 0.4));
        OsmFrPolygonCrawler crawler = crawler(1);

        assertEquals("europe/andorra", crawler.findCountryPath("europe/andorra", "andorra", "Andorra").orElseThrow());
        assertEquals("europe/andorra", crawler.findCountryPath("eu/andorra", "andorra", "Andorra").orElseThrow());
        List<PolyCandidate> found = crawler.crawl("europe/andorra");
        assertEquals(List.of("andorra"), found.stream().map(PolyCandidate::name).toList());
    }

    @Test
    void parseListingKeepsOnlyRelativeEntries() {
        assertEquals(List.of("brasil/", "brasil.poly"),
                OsmFrPolygonCrawler.parseListing(listing("brasil/", "brasil.poly", "/abs/", "https://x/y.poly")));
    }
}
