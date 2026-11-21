package io.github.fernandoglatz.igpsmap.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.net.DiskCache;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.StubServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialCatalogTest {

    @TempDir
    Path dir;

    static String mini() throws IOException {
        try (InputStream in = OfficialCatalogTest.class.getResourceAsStream("/catalog-mini.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheRegionTreeSortedByName() throws IOException {
        List<CatalogNode> roots = OfficialCatalog.parse(mini());

        assertEquals(List.of("europe", "south-america"), roots.stream().map(CatalogNode::name).toList());
        CatalogNode southAmerica = roots.get(1);
        assertEquals(List.of("Azerbaijan", "Brazil", "Trinidad and Tobago"),
                southAmerica.children().stream().map(CatalogNode::name).toList());
        CatalogNode brazil = southAmerica.children().get(1);
        assertFalse(brazil.downloadable());
        assertEquals(List.of("Distrito Federal", "São Paulo"), brazil.children().stream().map(CatalogNode::name).toList());
        CatalogNode df = brazil.children().get(0);
        assertEquals(List.of("south-america", "Brazil", "Distrito Federal"), df.path());
        assertEquals(4_000_000, df.size());
        assertEquals(URI.create("https://cdn.example/Mapinfo/V1/BR0100.zip"), df.url());
        assertEquals(Optional.of("BR0100"), df.codePrefix());
    }

    @Test
    void fixesBackslashesInDownloadLinks() throws IOException {
        CatalogNode tt = OfficialCatalog.parse(mini()).get(1).children().get(2);
        assertEquals(URI.create("https://cdn.example/Mapinfo/south-america/TT000023090922I2YE016014.map"), tt.url());
        assertEquals(Optional.of("TT0000"), tt.codePrefix());
    }

    @Test
    void linksWithoutAMapCodeHaveNoPrefix() throws IOException {
        CatalogNode az = OfficialCatalog.parse(mini()).get(1).children().get(0);
        assertTrue(az.downloadable());
        assertTrue(az.codePrefix().isEmpty());
    }

    @Test
    void leavesListsEveryDownloadableNode() throws IOException {
        List<CatalogNode> roots = OfficialCatalog.parse(mini());
        assertEquals(List.of("Portugal", "Azerbaijan", "Distrito Federal", "São Paulo", "Trinidad and Tobago"),
                CatalogNode.leaves(roots).stream().map(CatalogNode::name).toList());
    }

    @Test
    void slovakiaAggregateGroupsItsRegionsWithoutDownloadingTheMissingPackage() throws IOException {
        for (String version : List.of("V1", "V2")) {
            String json = """
                    {"code":0,"data":[{"id":"sk","name":"Slovakia",
                    "mapPathUrl":"https://cdn.example/Mapinfo/%s/SL0000.zip","subList":[
                      {"id":"sk1","name":"Banska Bystrica","mapPathUrl":"https://cdn.example/SL0100.zip"},
                      {"id":"sk2","name":"Bratislava","mapPathUrl":"https://cdn.example/SL0200.zip"}
                    ]}]}
                    """.formatted(version);
            CatalogNode country = OfficialCatalog.parse(json).get(0);
            assertFalse(country.downloadable());
            assertEquals(List.of("Banska Bystrica", "Bratislava"),
                    CatalogNode.leaves(List.of(country)).stream().map(CatalogNode::name).toList());
        }
    }

    @Test
    void standaloneSlovakiaPackagesAreNotHidden() throws IOException {
        CatalogNode node = OfficialCatalog.parse("""
                {"code":0,"data":[{"name":"Slovakia","mapPathUrl":"https://cdn.example/Mapinfo/V1/SL0000.zip"}]}
                """).get(0);
        assertTrue(node.downloadable());
    }

    @Test
    void wrongLiechtensteinLinkUsesTheVerifiedGenerationTemplate() throws IOException {
        CatalogNode node = OfficialCatalog.parse("""
                {"code":0,"data":[{"id":"li","name":"Liechtenstein","mapSize":265485,"mapVersion":"230310",
                 "mapPathUrl":"https://cdn.example/Mapinfo/asia/IL00002303103RF2JE01202W.map"}]}
                """).get(0);
        assertEquals(URI.create("https://qw20191008.oss-us-west-1.aliyuncs.com/8325946d-bcc5-4d00-8a74-67602f23140a"), node.url());
        assertEquals(1628052, node.size());
        assertEquals("20260823", node.version());
        assertEquals("li", node.id());
        assertEquals(List.of("Liechtenstein"), node.path());
    }

    @Test
    void validLiechtensteinAndIsraelEntriesAreNotRewritten() throws IOException {
        List<CatalogNode> nodes = OfficialCatalog.parse("""
                {"code":0,"data":[
                 {"name":"Liechtenstein","mapPathUrl":"https://cdn.example/LI0000.zip","mapVersion":"new"},
                 {"name":"Israel","mapPathUrl":"https://cdn.example/Mapinfo/asia/IL00002303103RF2JE01202W.map"}
                ]}
                """);
        assertEquals(URI.create("https://cdn.example/Mapinfo/asia/IL00002303103RF2JE01202W.map"), nodes.get(0).url());
        assertEquals(URI.create("https://cdn.example/LI0000.zip"), nodes.get(1).url());
        assertEquals("new", nodes.get(1).version());
    }

    @Test
    void apiErrorsAreReported() {
        IOException e = assertThrows(IOException.class,
                () -> OfficialCatalog.parse("{\"code\":401,\"msg\":\"login required\",\"data\":null}"));
        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertThrows(IOException.class, () -> OfficialCatalog.parse("<html>"));
    }

    @Test
    void malformedNumbersDoNotLoseTheCatalog() throws IOException {
        List<CatalogNode> roots = OfficialCatalog.parse("{\"code\":\"0\",\"data\":[{\"id\":\"x\",\"name\":\"Andorra\","
                + "\"mapSize\":\"about 2 MB\",\"mapPathUrl\":\"https://h/AD00.zip\"}]}");
        assertEquals(1, roots.size());
        assertEquals(0, roots.get(0).size());
        assertThrows(IOException.class, () -> OfficialCatalog.parse("{\"code\":\"oops\",\"data\":[]}"));
    }

    @Test
    void nodesWithoutLinksAreNotDownloadable() throws IOException {
        CatalogNode europe = OfficialCatalog.parse(mini()).get(0);
        assertNull(europe.url());
        assertFalse(europe.downloadable());
    }

    @Test
    void loadsTheProductCatalogOnceAndCachesIt() throws IOException {
        try (StubServer server = new StubServer()) {
            String body = mini();
            server.handle("/api/Map/MapInfoList", ex -> StubServer.send(ex, 200, body.getBytes(StandardCharsets.UTF_8)));
            HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
            DiskCache cache = new DiskCache(dir, Duration.ofDays(7), Clock.systemUTC());
            OfficialCatalog catalog = new OfficialCatalog(fetcher, cache, server.uri("/api/"));

            assertEquals(2, catalog.load(Product.IGS630S).size());
            assertEquals(2, catalog.load(Product.IGS630S).size());

            assertEquals(1, server.requests.size());
            assertEquals("/api/Map/MapInfoList", server.requests.get(0).path());
        }
    }

    @Test
    void apiErrorsAreNotCached() throws IOException {
        try (StubServer server = new StubServer()) {
            server.handle("/api/Map/MapInfoList", ex -> StubServer.send(ex, 200,
                    "{\"code\":500,\"message\":\"busy\"}".getBytes(StandardCharsets.UTF_8)));
            HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
            OfficialCatalog catalog = new OfficialCatalog(fetcher,
                    new DiskCache(dir, Duration.ofDays(7), Clock.systemUTC()), server.uri("/api/"));
            assertThrows(IOException.class, () -> catalog.load(Product.IGS800));
            assertThrows(IOException.class, () -> catalog.load(Product.IGS800));
            assertEquals(2, server.requests.size());
        }
    }
}
