package io.github.fernandoglatz.igpsmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.config.AppPaths;
import io.github.fernandoglatz.igpsmap.config.Settings;
import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.matching.Confidence;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import io.github.fernandoglatz.igpsmap.matching.PolySource;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.StubServer;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationServiceTest {

    private static final String POLY = "p\n1\n0 0\n1 0\n1 1\nEND\nEND\n";
    private static final long SIXTEEN_GIB = 16L * 1024 * 1024 * 1024;
    private static final Instant PBF_MODIFIED = Instant.parse("2026-01-12T00:00:00Z");

    @TempDir
    Path dir;

    private StubServer server;
    private AppPaths paths;
    private Settings settings;
    private final List<List<String>> commands = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = new StubServer();
        server.handle("/brazil-latest.osm.pbf", ex -> {
            ex.getResponseHeaders().add("Last-Modified",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(PBF_MODIFIED.atOffset(ZoneOffset.UTC)));
            StubServer.send(ex, 200, "pbf-bytes".getBytes(StandardCharsets.US_ASCII));
        });
        server.handle("/polygons/df.poly", ex -> StubServer.send(ex, 200, POLY.getBytes(StandardCharsets.US_ASCII)));
        paths = AppPaths.detect(dir, "Linux", dir.resolve("home"), Map.of());
        settings = Settings.defaults(paths);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private MapJob job(String fileName, PolySource poly) {
        MapFileName name = MapFileName.parse(fileName).orElseThrow();
        return new MapJob(new DetectedMap(dir.resolve("device").resolve(fileName), name), poly,
                server.uri("/brazil-latest.osm.pbf"), "brazil", Confidence.CSV, 1.0, List.of(), List.of(), null);
    }

    private PolySource remotePoly() {
        return new PolySource.Remote(server.uri("/polygons/df.poly"), "df");
    }

    /** Fake osmosis: writes the DF fixture to the --mapfile-writer file= path. */
    private ProcessRunner fakeOsmosis(int failOnCall, boolean writeOutput) {
        return (cmd, lines, cancel) -> {
            commands.add(cmd);
            lines.accept("fake osmosis running");
            if (commands.size() == failOnCall) {
                return 1;
            }
            if (writeOutput) {
                String out = cmd.get(cmd.indexOf("--mapfile-writer") + 1).substring("file=".length());
                Files.write(Path.of(out), MapFixtures.distritoFederalMap());
            }
            return 0;
        };
    }

    private GenerationService service(ProcessRunner runner) {
        HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
        return new GenerationService(fetcher, runner, paths, settings, Path.of("/jre/bin/java"), "cp.jar",
                List.of(dir.resolve("no-overrides")), () -> SIXTEEN_GIB);
    }

    private static final class RecordingListener implements GenerationListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onJobStart(int index, int total, MapJob job) {
            events.add("start " + index + "/" + total);
        }

        @Override
        public void onStep(String step) {
            events.add("step " + step);
        }

        @Override
        public void onProgress(double fraction) {
        }

        @Override
        public void onLog(String line) {
            events.add("log " + line);
        }

        @Override
        public void onDownload(long done, long total) {
            events.add("bytes " + done + "/" + total);
        }

        @Override
        public void onJobDone(JobResult result) {
            events.add("done " + result.status());
        }
    }

    private List<String> outputFiles() throws IOException {
        if (!Files.isDirectory(paths.output())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(paths.output())) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void generatesAllJobsWithIgpsportNames() throws IOException {
        List<JobResult> results = service(fakeOsmosis(-1, true)).runAll(List.of(
                job("BR01002303102B83FO00N00E.map", remotePoly()),
                job("BR02002303102833DN04O04Q.map", remotePoly())), new RecordingListener(), new CancelToken());

        assertEquals(List.of(JobResult.Status.SUCCESS, JobResult.Status.SUCCESS),
                results.stream().map(JobResult::status).toList());
        // Both fake outputs carry the DF bbox; the region code keeps them apart. Date comes from the PBF mtime.
        assertTrue(outputFiles().contains("BR0100" + "260112" + "2B83FO00N00E.map"), outputFiles().toString());
        assertTrue(outputFiles().contains("BR0200" + "260112" + "2B83FO00N00E.map"));
        assertTrue(Files.exists(paths.download().resolve("brazil-latest.osm.pbf")));
        List<String> cmd = commands.get(0);
        assertTrue(cmd.contains("file=" + paths.download().resolve("polygons/127.0.0.1/polygons/df.poly")), cmd.toString());
        // Auto settings and a tiny PBF: RAM writer with two threads, heap capped at 80% of 16 GiB.
        assertEquals("threads=2", cmd.get(cmd.size() - 2));
        assertTrue(cmd.contains("type=ram") && cmd.contains("-Xmx13107m"), cmd.toString());
    }

    @Test
    void reportsDownloadedBytesOfThePbf() {
        RecordingListener listener = new RecordingListener();
        service(fakeOsmosis(-1, true)).runAll(List.of(job("BR01002303102B83FO00N00E.map", remotePoly())),
                listener, new CancelToken());
        // "pbf-bytes" is 9 bytes long.
        assertTrue(listener.events.contains("bytes 9/9"), listener.events.toString());
    }

    @Test
    void cancelInterruptsADownloadThatIsWaitingForTheServer() throws Exception {
        server.handle("/stalled.osm.pbf", ex -> {
            if (!"HEAD".equals(ex.getRequestMethod())) {
                try {
                    Thread.sleep(10_000); // server accepted the request but sends nothing
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            StubServer.send(ex, 200, "late".getBytes(StandardCharsets.US_ASCII));
        });
        MapJob stalled = new MapJob(job("BR01002303102B83FO00N00E.map", remotePoly()).map(), remotePoly(),
                server.uri("/stalled.osm.pbf"), "stalled", Confidence.CSV, 1.0, List.of(), List.of(), null);
        CancelToken cancel = new CancelToken();
        java.util.concurrent.atomic.AtomicReference<List<JobResult>> results = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> results.set(
                service(fakeOsmosis(-1, true)).runAll(List.of(stalled), new RecordingListener(), cancel)));
        worker.start();
        Thread.sleep(500);
        long cancelledAt = System.nanoTime();
        cancel.cancel();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "Cancel did not stop the stalled download");
        assertTrue(System.nanoTime() - cancelledAt < 3_000_000_000L);
        assertEquals(JobResult.Status.CANCELLED, results.get().get(0).status(), results.get().get(0).message());
    }

    @Test
    void logsTheUpdateCheckBeforeDownloading() {
        RecordingListener listener = new RecordingListener();
        service(fakeOsmosis(-1, true)).runAll(List.of(job("BR01002303102B83FO00N00E.map", remotePoly())),
                listener, new CancelToken());
        int check = listener.events.indexOf("log Checking for newer data: brazil-latest.osm.pbf");
        int download = listener.events.indexOf("log Downloading " + server.uri("/brazil-latest.osm.pbf"));
        assertTrue(check >= 0 && check < download, listener.events.toString());
    }

    @Test
    void failedJobDoesNotStopTheQueueOrLeaveTempMaps() throws IOException {
        settings = withFallback(false);
        RecordingListener listener = new RecordingListener();
        List<JobResult> results = service(fakeOsmosis(1, true)).runAll(List.of(
                job("BR01002303102B83FO00N00E.map", remotePoly()),
                job("BR02002303102833DN04O04Q.map", remotePoly())), listener, new CancelToken());

        assertEquals(JobResult.Status.FAILED, results.get(0).status());
        assertTrue(results.get(0).message().contains("exit code 1"), results.get(0).message());
        assertEquals(JobResult.Status.SUCCESS, results.get(1).status());
        assertEquals(1, outputFiles().size());
        try (Stream<Path> tmp = Files.list(paths.tmp())) {
            assertTrue(tmp.noneMatch(p -> p.getFileName().toString().endsWith(".map")));
        }
        assertTrue(listener.events.contains("log fake osmosis running"));
    }

    @Test
    void missingOutputIsAFailure() {
        List<JobResult> results = service(fakeOsmosis(-1, false)).runAll(
                List.of(job("BR01002303102B83FO00N00E.map", remotePoly())), new RecordingListener(), new CancelToken());
        assertEquals(JobResult.Status.FAILED, results.get(0).status());
        assertEquals("osmosis produced no file", results.get(0).message());
    }

    @Test
    void boundingBoxPolygonIsWrittenLocally() throws IOException {
        PolySource bbox = new PolySource.FromBoundingBox(new BoundingBox(-16, -48, -15, -47));
        service(fakeOsmosis(-1, true)).runAll(List.of(job("BR01002303102B83FO00N00E.map", bbox)),
                new RecordingListener(), new CancelToken());
        Path poly = paths.download().resolve("bbox-BR01002B83FO00N00E.poly");
        assertTrue(Files.readString(poly).startsWith("bbox-BR01002B83FO00N00E\n"));
        assertTrue(commands.get(0).contains("file=" + poly));
    }

    @Test
    void cancelMarksCurrentJobCancelledAndSkipsTheRest() {
        CancelToken cancel = new CancelToken();
        ProcessRunner cancelling = (cmd, lines, token) -> {
            token.cancel();
            return 143;
        };
        List<JobResult> results = service(cancelling).runAll(List.of(
                job("BR01002303102B83FO00N00E.map", remotePoly()),
                job("BR02002303102833DN04O04Q.map", remotePoly())), new RecordingListener(), cancel);
        assertEquals(List.of(JobResult.Status.CANCELLED, JobResult.Status.SKIPPED),
                results.stream().map(JobResult::status).toList());
    }

    @Test
    void unmatchedJobIsSkipped() {
        MapJob unmatched = new MapJob(job("ZZ01002303102B83FO00N00E.map", null).map(), null, null, null,
                Confidence.UNMATCHED, 0, List.of(), List.of(), "no country");
        List<JobResult> results = service(fakeOsmosis(-1, true)).runAll(List.of(unmatched),
                new RecordingListener(), new CancelToken());
        assertEquals(JobResult.Status.SKIPPED, results.get(0).status());
    }

    @Test
    void corruptCachedPolygonIsDownloadedAgain() throws IOException {
        Path cached = paths.download().resolve("polygons/127.0.0.1/polygons/df.poly");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "<html>error page</html>");

        List<JobResult> results = service(fakeOsmosis(-1, true)).runAll(
                List.of(job("BR01002303102B83FO00N00E.map", remotePoly())), new RecordingListener(), new CancelToken());

        assertEquals(JobResult.Status.SUCCESS, results.get(0).status(), results.get(0).message());
        assertEquals(POLY, Files.readString(cached));
    }

    @Test
    void downloadPathMirrorsHostAndPath() {
        Path download = dir.resolve("download");
        assertEquals(download.resolve("polygons/osm.fr/polygons/europe/portugal.poly"),
                GenerationService.downloadPath(download.resolve("polygons"),
                        java.net.URI.create("https://osm.fr/polygons/europe/portugal.poly")));
    }

    @Test
    void downloadPathKeepsQueryStringsApart() {
        Path base = dir.resolve("download/polygons");
        Path a = GenerationService.downloadPath(base, java.net.URI.create("https://polygons.openstreetmap.fr/get_poly.py?id=1&params=0"));
        Path b = GenerationService.downloadPath(base, java.net.URI.create("https://polygons.openstreetmap.fr/get_poly.py?id=2&params=0"));
        assertFalse(a.equals(b), a + " vs " + b);
        assertTrue(a.normalize().startsWith(base), a.toString());
    }

    @Test
    void polygonNotYetGeneratedIsRequestedThenRetried() throws IOException {
        java.util.concurrent.atomic.AtomicBoolean generated = new java.util.concurrent.atomic.AtomicBoolean();
        server.handle("/index.py", ex -> {
            generated.set(true);
            StubServer.send(ex, 200, "<html>generating</html>".getBytes(StandardCharsets.US_ASCII));
        });
        server.handle("/get_poly.py", ex -> StubServer.send(ex, 200,
                (generated.get() ? POLY : "None").getBytes(StandardCharsets.US_ASCII)));
        PolySource osm = new PolySource.Remote(server.uri("/get_poly.py?id=42&params=0"), "Chiba (OSM)");

        List<JobResult> results = service(fakeOsmosis(-1, true)).runAll(
                List.of(job("JP04002303105LY2H9011016.map", osm)), new RecordingListener(), new CancelToken());

        assertEquals(JobResult.Status.SUCCESS, results.get(0).status(), results.get(0).message());
        assertTrue(server.requests.stream().anyMatch(r -> r.path().equals("/index.py")));
    }

    @Test
    void downloadPathNeverLeavesTheFolder() {
        Path base = dir.resolve("download/polygons");
        for (String url : List.of("https://h/a/../../../../../../escape.poly", "https://h/%2e%2e/%2e%2e/x.poly",
                "https://h/", "https://h/a/..")) {
            Path p = GenerationService.downloadPath(base, java.net.URI.create(url));
            assertTrue(p.normalize().startsWith(base) && !p.normalize().equals(base), url + " -> " + p);
        }
    }

    @Test
    void freshPbfIsNotDownloadedAgain() throws IOException {
        Path pbf = paths.download().resolve("brazil-latest.osm.pbf");
        Files.createDirectories(pbf.getParent());
        Files.writeString(pbf, "cached");
        Files.setLastModifiedTime(pbf, FileTime.from(PBF_MODIFIED.plusSeconds(60)));

        service(fakeOsmosis(-1, true)).runAll(List.of(job("BR01002303102B83FO00N00E.map", remotePoly())),
                new RecordingListener(), new CancelToken());

        assertEquals("cached", Files.readString(pbf));
        assertFalse(server.requests.stream().anyMatch(r -> r.method().equals("GET") && r.path().endsWith(".pbf")));
    }

    @Test
    void stalePbfIsDownloadedAgain() throws IOException {
        Path pbf = paths.download().resolve("brazil-latest.osm.pbf");
        Files.createDirectories(pbf.getParent());
        Files.writeString(pbf, "old");
        Files.setLastModifiedTime(pbf, FileTime.from(PBF_MODIFIED.minusSeconds(86_400)));

        service(fakeOsmosis(-1, true)).runAll(List.of(job("BR01002303102B83FO00N00E.map", remotePoly())),
                new RecordingListener(), new CancelToken());

        assertEquals("pbf-bytes", Files.readString(pbf));
    }

    private Settings withFallback(boolean fallback) {
        return new Settings(settings.outputDir(), Settings.AUTO, Settings.AUTO, settings.cacheMaxAge(), null, fallback, null);
    }

    @Test
    void retryInDiskModeIsOnByDefault() {
        assertTrue(Settings.defaults(paths).hdFallback());
        List<JobResult> results = service(fakeOsmosis(1, true)).runAll(
                List.of(job("BR01002303102B83FO00N00E.map", remotePoly())), new RecordingListener(), new CancelToken());
        assertEquals(JobResult.Status.SUCCESS, results.get(0).status());
        assertEquals(2, commands.size());
    }

    @Test
    void ramFailureWithoutFallbackFailsWithAWarning() {
        settings = withFallback(false);
        List<JobResult> results = service(fakeOsmosis(1, true)).runAll(
                List.of(job("BR01002303102B83FO00N00E.map", remotePoly())), new RecordingListener(), new CancelToken());
        assertEquals(JobResult.Status.FAILED, results.get(0).status());
        assertEquals(1, commands.size());
        assertTrue(commands.get(0).contains("type=ram"));
        assertEquals("Memory mode failed (exit 1). Turn on \"Retry in disk mode if memory runs out\" "
                + "or raise Max memory.", results.get(0).warning());
    }

    @Test
    void successfulRetryCarriesAWarning() {
        settings = withFallback(true);
        List<JobResult> results = service(fakeOsmosis(1, true)).runAll(
                List.of(job("BR01002303102B83FO00N00E.map", remotePoly())), new RecordingListener(), new CancelToken());
        assertEquals(JobResult.Status.SUCCESS, results.get(0).status());
        assertEquals("Memory mode failed (exit 1); generated in disk mode instead.", results.get(0).warning());
    }

    @Test
    void failedRetryCarriesAWarning() {
        settings = withFallback(true);
        ProcessRunner alwaysFails = (cmd, lines, token) -> {
            commands.add(cmd);
            return 1;
        };
        List<JobResult> results = service(alwaysFails).runAll(
                List.of(job("BR01002303102B83FO00N00E.map", remotePoly())), new RecordingListener(), new CancelToken());
        assertEquals(JobResult.Status.FAILED, results.get(0).status());
        assertEquals("Memory mode failed (exit 1) and the disk-mode retry failed too.", results.get(0).warning());
    }

    @Test
    void normalRunsHaveNoWarning() {
        List<JobResult> results = service(fakeOsmosis(-1, true)).runAll(
                List.of(job("BR01002303102B83FO00N00E.map", remotePoly())), new RecordingListener(), new CancelToken());
        assertEquals(null, results.get(0).warning());
        assertEquals(List.of(), JobResult.warnings(results));
    }

    @Test
    void warningsListTheMapAndTheReason() {
        settings = withFallback(true);
        List<JobResult> results = service(fakeOsmosis(1, true)).runAll(
                List.of(job("BR01002303102B83FO00N00E.map", remotePoly())), new RecordingListener(), new CancelToken());
        assertEquals(List.of("BR01002303102B83FO00N00E.map: Memory mode failed (exit 1); generated in disk mode instead."),
                JobResult.warnings(results));
    }

    @Test
    void ramFailureWithFallbackRetriesInDiskMode() {
        settings = withFallback(true);
        RecordingListener listener = new RecordingListener();
        List<JobResult> results = service(fakeOsmosis(1, true)).runAll(
                List.of(job("BR01002303102B83FO00N00E.map", remotePoly())), listener, new CancelToken());
        assertEquals(JobResult.Status.SUCCESS, results.get(0).status(), results.get(0).message());
        assertEquals(2, commands.size());
        assertTrue(commands.get(1).contains("type=hd"), commands.get(1).toString());
        assertTrue(commands.get(1).contains("-Xmx8192m"), commands.get(1).toString());
        assertTrue(listener.events.contains("log RAM writer failed (exit 1); retrying in disk mode (hd)"),
                listener.events.toString());
    }

    @Test
    void cancelDuringTheRamRunDoesNotRetry() {
        settings = withFallback(true);
        ProcessRunner cancelling = (cmd, lines, token) -> {
            commands.add(cmd);
            token.cancel();
            return 1;
        };
        List<JobResult> results = service(cancelling).runAll(
                List.of(job("BR01002303102B83FO00N00E.map", remotePoly())), new RecordingListener(), new CancelToken());
        assertEquals(JobResult.Status.CANCELLED, results.get(0).status());
        assertEquals(1, commands.size());
    }

    @Test
    void logsTheWriterDecision() {
        RecordingListener listener = new RecordingListener();
        service(fakeOsmosis(-1, true)).runAll(List.of(job("BR01002303102B83FO00N00E.map", remotePoly())),
                listener, new CancelToken());
        assertTrue(listener.events.stream().anyMatch(e -> e.startsWith("log Writer: ram, 2 threads")),
                listener.events.toString());
    }

    @Test
    void manualSettingsAreUsedAsGiven() {
        settings = new Settings(settings.outputDir(), 6, 10, settings.cacheMaxAge(), null, false, null);
        service(fakeOsmosis(-1, true)).runAll(List.of(job("BR01002303102B83FO00N00E.map", remotePoly())),
                new RecordingListener(), new CancelToken());
        assertTrue(commands.get(0).contains("threads=6") && commands.get(0).contains("-Xmx10240m"),
                commands.get(0).toString());
    }
}
