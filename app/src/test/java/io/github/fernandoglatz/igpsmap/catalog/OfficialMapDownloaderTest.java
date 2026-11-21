package io.github.fernandoglatz.igpsmap.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.StubServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialMapDownloaderTest {

    private static final String MAIN = "FR940024071744O3J900E00D.map";
    private static final byte[] MAP_BYTES = "mapsforge binary OSM main map".repeat(500).getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path dir;

    private StubServer server;
    private OfficialMapDownloader downloader;
    private Path catalogDir;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubServer();
        HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
        downloader = new OfficialMapDownloader(fetcher, dir.resolve("originals"));
        catalogDir = dir.resolve("originals").resolve(Product.IGS800.catalogId());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** iGS800-style package: main map, overview, contour layer and routing files. */
    private byte[] fr94() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("Maps/" + MAIN, MAP_BYTES);
        entries.put("Maps/FR9400C.map", new byte[] {1, 2, 3});
        entries.put("Maps/FR94A024071709A07Y001001.map", new byte[] {4, 5, 6});
        entries.put("Router/FR9400E.rtd", new byte[100]);
        return zip(entries);
    }

    private CatalogNode node(String name, String path) {
        return new CatalogNode("id-" + name, name, List.of("europe", name), 123, "2407", server.uri(path), List.of());
    }

    @Test
    void extractsOnlyTheMainMapOfAPackage() throws IOException {
        byte[] zip = fr94();
        server.handle("/Mapinfo/iGS800/FR94.zip", ex -> StubServer.sendRange(ex, zip));

        OfficialMapDownloader.Result result = downloader.fetch(node("Reunion", "/Mapinfo/iGS800/FR94.zip"),
                Product.IGS800, (d, t) -> { });

        assertNull(result.error());
        assertEquals(List.of(catalogDir.resolve(MAIN)), result.maps());
        assertArrayEquals(MAP_BYTES, Files.readAllBytes(result.maps().get(0)));
        try (Stream<Path> files = Files.list(catalogDir)) {
            assertEquals(List.of(MAIN), files.map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith(".")).toList());
        }
    }

    @Test
    void igs630CanUseOnlyTheMainMapOfTheLiechtensteinV3Template() throws IOException {
        String main = "LI00002608233BR27S004007.map";
        byte[] archive = zip(Map.of(
                "LI00/Maps/" + main, MAP_BYTES,
                "LI00/Maps/LI00A026082307H04Z000000.map", new byte[] {1, 2},
                "LI00/Router/LI0000E.rtd", new byte[] {3, 4}));
        server.handle("/liechtenstein-template", ex -> StubServer.sendRange(ex, archive));
        OfficialMapDownloader.Result result = downloader.fetch(node("Liechtenstein", "/liechtenstein-template"),
                Product.IGS630, (d, t) -> { });

        assertNull(result.error());
        Path original = dir.resolve("originals").resolve(Product.IGS630.catalogId()).resolve(main);
        assertEquals(List.of(original), result.maps());
        assertArrayEquals(MAP_BYTES, Files.readAllBytes(original));
        try (Stream<Path> files = Files.walk(original.getParent())) {
            assertEquals(List.of(original), files.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(".fetched")).toList());
        }
    }

    @Test
    void downloadsRawMapsAtUuidUrlsUsingTheirContentDisposition() throws IOException {
        String[] names = {"BJ00002611153692XU01Y045.map", "MA00002511202UA2H80AV0BD.map",
                "AZ00002611153Y22BW04E03O.map", "GE00002503173V02AR04C02K.map",
                "BM000026111520K2JX00I00H.map", "ES35002611152TT2LP04302S.map"};
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            String route = "/uuid-" + i;
            server.handle(route, ex -> {
                ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + name + "\"");
                StubServer.sendRange(ex, MAP_BYTES);
            });
            OfficialMapDownloader.Result result = downloader.fetch(node("Raw " + i, route), Product.IGS630, (d,t) -> { });
            assertNull(result.error(), result.error());
            assertEquals(name, result.maps().get(0).getFileName().toString());
            assertArrayEquals(MAP_BYTES, Files.readAllBytes(result.maps().get(0)));
        }
    }

    @Test
    void rawMapProbeWorksWhenServerIgnoresRangeAndUsesEncodedFilename() throws IOException {
        String name = "ES35002611152TT2LP04302S.map";
        server.handle("/encoded-uuid", ex -> {
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename*=UTF-8''" + name);
            StubServer.send(ex, 200, MAP_BYTES);
        });
        var result = downloader.fetch(node("Encoded", "/encoded-uuid"), Product.IGS630, (d,t) -> { });
        assertNull(result.error(), result.error());
        assertEquals(name, result.maps().get(0).getFileName().toString());
    }

    @Test
    void unsafeOrMissingRawMapFilenameIsRejected() {
        server.handle("/bad-uuid", ex -> {
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"../../settings.properties\"");
            StubServer.sendRange(ex, MAP_BYTES);
        });
        var result = downloader.fetch(node("Bad", "/bad-uuid"), Product.IGS630, (d,t) -> { });
        assertEquals(List.of(), result.maps());
        assertTrue(result.error().contains("unexpected map file name"));
        assertFalse(Files.exists(dir.resolve("settings.properties")));
        server.handle("/unnamed-uuid", ex -> StubServer.sendRange(ex, MAP_BYTES));
        var unnamed = downloader.fetch(node("Unnamed", "/unnamed-uuid"), Product.IGS630, (d,t) -> { });
        assertEquals(List.of(), unnamed.maps());
        assertTrue(unnamed.error().contains("unexpected map file name"));
    }

    @Test
    void reusesMapsDownloadedBefore() throws IOException {
        byte[] zip = fr94();
        server.handle("/FR94.zip", ex -> StubServer.sendRange(ex, zip));
        CatalogNode node = node("Reunion", "/FR94.zip");
        downloader.fetch(node, Product.IGS800, (d, t) -> { });
        int requests = server.requests.size();

        OfficialMapDownloader.Result again = downloader.fetch(node, Product.IGS800, (d, t) -> { });

        assertEquals(List.of(catalogDir.resolve(MAIN)), again.maps());
        assertEquals(requests, server.requests.size());
    }

    @Test
    void downloadsBareMapFiles() throws IOException {
        server.handle("/Mapinfo/south-america/TT000023090922I2YE016014.map", ex -> StubServer.send(ex, 200, MAP_BYTES));

        OfficialMapDownloader.Result result = downloader.fetch(
                node("Trinidad", "/Mapinfo/south-america/TT000023090922I2YE016014.map"), Product.IGS800, (d, t) -> { });

        assertNull(result.error());
        assertEquals(List.of(catalogDir.resolve("TT000023090922I2YE016014.map")), result.maps());
    }

    @Test
    void packageWithoutAUsableMapIsAnError() throws IOException {
        byte[] zip = zip(Map.of("VN00/Router/VN.irtd", new byte[10], "VN00/Maps/055/T1.imap", new byte[10]));
        server.handle("/VN00.zip", ex -> StubServer.sendRange(ex, zip));

        OfficialMapDownloader.Result result = downloader.fetch(node("Vietnam", "/VN00.zip"), Product.IGS800, (d, t) -> { });

        assertEquals(List.of(), result.maps());
        assertEquals("No usable map in Vietnam", result.error());
    }

    @Test
    void nonZipDownloadsAreAnError() {
        server.handle("/3ba56f27", ex -> StubServer.sendRange(ex, "<html>nope</html>".getBytes(StandardCharsets.US_ASCII)));
        OfficialMapDownloader.Result result = downloader.fetch(node("Azerbaijan", "/3ba56f27"), Product.IGS800, (d, t) -> { });
        assertEquals("Azerbaijan is not a map package", result.error());
    }

    @Test
    void httpErrorsBecomeResultErrors() {
        server.handle("/gone.zip", ex -> StubServer.send(ex, 404, new byte[0]));
        OfficialMapDownloader.Result result = downloader.fetch(node("Gone", "/gone.zip"), Product.IGS800, (d, t) -> { });
        assertTrue(result.error().startsWith("Gone: "), result.error());
    }

    @Test
    void serversWithoutRangeSupportGetAFullDownload() throws IOException {
        byte[] zip = fr94();
        server.handle("/FR94.zip", ex -> StubServer.send(ex, 200, zip));

        OfficialMapDownloader.Result result = downloader.fetch(node("Reunion", "/FR94.zip"), Product.IGS800, (d, t) -> { });

        assertNull(result.error());
        assertEquals(List.of(catalogDir.resolve(MAIN)), result.maps());
        try (Stream<Path> files = Files.list(catalogDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".zip")));
        }
    }

    @Test
    void cancellationPropagatesAndLeavesNoMap() throws IOException {
        byte[] zip = fr94();
        server.handle("/FR94.zip", ex -> StubServer.sendRange(ex, zip));
        assertThrows(CancellationException.class, () -> downloader.fetch(node("Reunion", "/FR94.zip"), Product.IGS800,
                (done, total) -> {
                    if (done > 0) {
                        throw new CancellationException();
                    }
                }));
        assertTrue(!Files.exists(catalogDir.resolve(MAIN)));
    }

    @Test
    void nodeWithoutLinkIsAnError() {
        CatalogNode none = new CatalogNode("x", "Europe", List.of("Europe"), 0, "", null, List.of());
        assertEquals("Europe has no download link", downloader.fetch(none, Product.IGS800, (d, t) -> { }).error());
    }

    @Test
    void codePrefixComesFromTheLinkFileName() {
        assertEquals("FR94", node("x", "/Mapinfo/iGS800/FR94.zip").codePrefix().orElseThrow());
        assertTrue(new CatalogNode("x", "x", List.of(), 0, "", URI.create("https://h/abc-def"), List.of())
                .codePrefix().isEmpty());
    }
}
