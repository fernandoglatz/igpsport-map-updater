package io.github.fernandoglatz.igpsmap.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteZipTest {

    private static final String MAP = "CN0200/Maps/CN020026042056P2D801D01E.map";
    private static final String RTD = "CN0200/Router/CN0200S.rtd";
    private static final String STORED = "CN0200/Maps/CN0200A026042056P2D801D01E.map";

    @TempDir
    Path dir;

    private StubServer server;
    private HttpFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubServer();
        fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static byte[] content(int size, long seed) {
        byte[] bytes = new byte[size];
        Random random = new Random(seed);
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ("mapsforge".charAt(i % 9) + random.nextInt(3)); // compressible but not trivial
        }
        return bytes;
    }

    /** Entries with a name starting with "stored:" are written uncompressed. */
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                boolean stored = e.getKey().startsWith("stored:");
                ZipEntry entry = new ZipEntry(stored ? e.getKey().substring(7) : e.getKey());
                if (stored) {
                    CRC32 crc = new CRC32();
                    crc.update(e.getValue());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(e.getValue().length);
                    entry.setCompressedSize(e.getValue().length);
                    entry.setCrc(crc.getValue());
                }
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private Map<String, byte[]> sample() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("CN0200/", new byte[0]);
        entries.put(MAP, content(200_000, 1));
        entries.put(RTD, content(150_000, 2));
        entries.put("stored:" + STORED, content(5_000, 3));
        return entries;
    }

    private java.net.URI serve(byte[] zip) {
        server.handle("/pkg.zip", ex -> StubServer.sendRange(ex, zip));
        return server.uri("/pkg.zip");
    }

    private RemoteZip.Entry entry(RemoteZip zip, String name) {
        return zip.entries().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void listsTheEntriesFromTheCentralDirectory() throws IOException {
        RemoteZip zip = RemoteZip.open(fetcher, serve(zip(sample())));
        assertEquals(List.of("CN0200/", MAP, RTD, STORED), zip.entries().stream().map(RemoteZip.Entry::name).toList());
        assertTrue(zip.entries().get(0).directory());
        assertEquals(200_000, entry(zip, MAP).size());
        assertEquals(8, entry(zip, MAP).method());
        assertEquals(0, entry(zip, STORED).method());
    }

    @Test
    void extractsDeflatedAndStoredEntriesWithRangeRequestsOnly() throws IOException {
        Map<String, byte[]> entries = sample();
        RemoteZip zip = RemoteZip.open(fetcher, serve(zip(entries)));
        Path map = dir.resolve("a.map");
        Path stored = dir.resolve("b.map");
        long[] last = new long[2];
        zip.extract(entry(zip, MAP), map, (done, total) -> {
            last[0] = done;
            last[1] = total;
        });
        zip.extract(entry(zip, STORED), stored, (done, total) -> { });

        assertArrayEquals(entries.get(MAP), Files.readAllBytes(map));
        assertArrayEquals(entries.get("stored:" + STORED), Files.readAllBytes(stored));
        assertEquals(200_000, last[0]);
        assertEquals(200_000, last[1]);
        assertTrue(server.requests.stream().allMatch(r -> r.range() != null), server.requests.toString());
    }

    @Test
    void corruptDataLeavesNoFileBehind() throws IOException {
        byte[] bytes = zip(sample());
        RemoteZip zip = RemoteZip.open(fetcher, serve(bytes));
        RemoteZip.Entry stored = entry(zip, STORED);
        // Flip one byte inside the stored entry's data (after its 30-byte header and file name).
        int dataStart = (int) stored.localHeaderOffset() + 30 + STORED.length();
        bytes[dataStart + 100] ^= 0x55;
        Path target = dir.resolve("b.map");

        IOException e = assertThrows(IOException.class, () -> zip.extract(stored, target, (d, t) -> { }));
        assertTrue(e.getMessage().contains("CRC"), e.getMessage());
        assertFalse(Files.exists(target));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    void cancellingDeletesThePartialFile() throws IOException {
        RemoteZip zip = RemoteZip.open(fetcher, serve(zip(sample())));
        Path target = dir.resolve("a.map");
        assertThrows(CancellationException.class, () -> zip.extract(entry(zip, MAP), target, (done, total) -> {
            if (done > 0) {
                throw new CancellationException();
            }
        }));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    void serverIgnoringRangesIsReported() throws IOException {
        byte[] bytes = zip(sample());
        server.handle("/full.zip", ex -> StubServer.send(ex, 200, bytes));
        assertThrows(RemoteZip.RangeNotSupportedException.class, () -> RemoteZip.open(fetcher, server.uri("/full.zip")));
    }

    @Test
    void nonZipContentIsRejected() {
        server.handle("/x", ex -> StubServer.sendRange(ex, "mapsforge binary OSM, not a zip".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(RemoteZip.NotAZipException.class, () -> RemoteZip.open(fetcher, server.uri("/x")));
    }

    @Test
    void readsZip64CentralDirectories() throws IOException {
        // Java switches to a ZIP64 end record once a zip has 65535 or more entries.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.setLevel(0);
            for (int i = 0; i < 65_536; i++) {
                out.putNextEntry(new ZipEntry("e/" + i));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(MAP));
            out.write(content(1_000, 4));
            out.closeEntry();
        }
        RemoteZip zip = RemoteZip.open(fetcher, serve(bytes.toByteArray()));
        assertEquals(65_537, zip.entries().size());
        Path target = dir.resolve("z.map");
        zip.extract(entry(zip, MAP), target, (d, t) -> { });
        assertArrayEquals(content(1_000, 4), Files.readAllBytes(target));
    }

    @Test
    void extractsLocallyOnlyTheWantedEntries() throws IOException {
        Map<String, byte[]> entries = sample();
        Path file = Files.write(dir.resolve("pkg.zip"), zip(entries));
        Path out = dir.resolve("out");

        List<Path> extracted = RemoteZip.extractLocally(file, name -> name.endsWith("D01E.map"), out);

        assertEquals(List.of(out.resolve("CN020026042056P2D801D01E.map"), out.resolve("CN0200A026042056P2D801D01E.map")),
                extracted);
        assertArrayEquals(entries.get(MAP), Files.readAllBytes(extracted.get(0)));
    }

    @Test
    void extractLocallyNeverWritesOutsideTheFolder() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a/..", new byte[] {1});
        entries.put("a/.", new byte[] {2});
        entries.put("ok.map", new byte[] {3});
        Path file = Files.write(dir.resolve("evil.zip"), zip(entries));
        Path out = dir.resolve("deep/out");

        List<Path> extracted = RemoteZip.extractLocally(file, name -> true, out);

        assertEquals(List.of(out.resolve("ok.map")), extracted);
        assertFalse(Files.isRegularFile(dir.resolve("deep")));
    }

    @Test
    void baseNameDropsFolders() {
        assertEquals("x.map", RemoteZip.baseName("a/b/x.map"));
        assertEquals("x.map", RemoteZip.baseName("a\\b\\x.map"));
        assertEquals("", RemoteZip.baseName("a/b/"));
    }
}
