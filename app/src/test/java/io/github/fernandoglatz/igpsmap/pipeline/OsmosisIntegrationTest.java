package io.github.fernandoglatz.igpsmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.io.MapHeaderReader;
import io.github.fernandoglatz.igpsmap.io.PolyFile;
import io.github.fernandoglatz.igpsmap.config.AppPaths;
import io.github.fernandoglatz.igpsmap.config.Settings;
import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.matching.Confidence;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import io.github.fernandoglatz.igpsmap.matching.PolySource;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.StubServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Arrays;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the real osmosis + Mapsforge writer in a child JVM. Opt-in: -Dsurefire.groups=integration. */
@Tag("integration")
class OsmosisIntegrationTest {

    @TempDir
    Path dir;

    private static final String OSM = """
            <?xml version='1.0' encoding='UTF-8'?>
            <osm version="0.6" generator="test">
              <node id="1" version="1" timestamp="2026-01-12T00:00:00Z" lat="-15.80" lon="-47.90"/>
              <node id="2" version="1" timestamp="2026-01-12T00:00:00Z" lat="-15.79" lon="-47.88"/>
              <node id="3" version="1" timestamp="2026-01-12T00:00:00Z" lat="-15.78" lon="-47.86"/>
              <way id="10" version="1" timestamp="2026-01-12T00:00:00Z">
                <nd ref="1"/><nd ref="2"/><nd ref="3"/>
                <tag k="highway" v="motorway"/>
              </way>
            </osm>
            """;

    @Test
    void compatibilityPipelineProducesReadablePatchedMapAndFinalChecksums() throws Exception {
        Path java = OsmosisCommand.currentJavaExecutable();
        String runtime = Path.of("target/classes").toAbsolutePath() + File.pathSeparator
                + Files.readString(Path.of("target/runtime-classpath.txt")).strip();
        String osmText = OSM.replace("v=\"motorway\"", "v=\"footway\"").replace("</osm>", """
                  <way id="11" version="1" timestamp="2026-01-12T00:00:00Z">
                    <nd ref="1"/><nd ref="3"/><tag k="highway" v="path"/>
                  </way>
                  <way id="12" version="1" timestamp="2026-01-12T00:00:00Z">
                    <nd ref="2"/><nd ref="3"/><tag k="waterway" v="river"/>
                  </way>
                </osm>
                """);
        Path osm = Files.writeString(dir.resolve("strict.osm"), osmText);
        Path pbf = dir.resolve("strict.osm.pbf");
        List<String> log = new CopyOnWriteArrayList<>();
        SystemProcessRunner runner = new SystemProcessRunner();
        int conversion = runner.run(List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                OsmosisCommand.MAIN_CLASS, "--read-xml", "file=" + osm, "--write-pbf", "file=" + pbf),
                log::add, new CancelToken());
        assertEquals(0, conversion, String.join("\n", log));
        try (StubServer server = new StubServer()) {
            byte[] pbfBytes = Files.readAllBytes(pbf);
            server.handle("/tiny.osm.pbf", ex -> StubServer.send(ex, 200, pbfBytes));
            AppPaths paths = AppPaths.detect(dir.resolve("app"), "Linux", dir.resolve("home"), Map.of());
            Settings settings = Settings.defaults(paths);
            String originalName = "BR01002303102B83FO00N00E.map";
            Path original = Files.write(dir.resolve(originalName), MapFixtures.completeMap("sourceQ"));
            MapJob job = new MapJob(new DetectedMap(original, MapFileName.parse(originalName).orElseThrow()),
                    new PolySource.FromBoundingBox(new BoundingBox(-16.07, -48.305, -15.48, -47.29)),
                    server.uri("/tiny.osm.pbf"), "tiny", Confidence.MANUAL, 1.0, List.of(), List.of(), null);
            GenerationListener listener = new GenerationListener() {
                public void onJobStart(int i, int n, MapJob j) { }
                public void onStep(String step) { log.add(step); }
                public void onProgress(double fraction) { }
                public void onLog(String text) { log.add(text); }
                public void onJobDone(JobResult result) { }
            };
            JobResult result = new GenerationService(new HttpFetcher(HttpClient.newHttpClient(), "test", Duration.ofSeconds(10)),
                    runner, paths, settings, java, runtime, List.of(), () -> 2L * 1024 * 1024 * 1024)
                    .runAll(List.of(job), listener, new CancelToken()).get(0);
            assertEquals(JobResult.Status.SUCCESS, result.status(), String.join("\n", log));
            assertTrue(result.warning() == null, result.warning());
            MapFile map = new MapFile(result.output().toFile());
            try {
                var info = map.getMapFileInfo();
                assertEquals("sourceQ", info.createdBy);
                assertEquals(3, info.fileVersion);
                assertEquals(0, info.poiTags.length);
                assertTrue(Arrays.stream(info.wayTags).anyMatch(t -> t.key.equals("highway") && t.value.equals("footway")));
                assertTrue(Arrays.stream(info.wayTags).anyMatch(t -> t.key.equals("highway") && t.value.equals("path")));
                assertTrue(Arrays.stream(info.wayTags).noneMatch(t -> t.key.equals("waterway")));
                int ways = 0;
                for (byte z = 13; z <= 14; z++) {
                    int left = MercatorProjection.longitudeToTileX(info.boundingBox.minLongitude, z);
                    int right = MercatorProjection.longitudeToTileX(info.boundingBox.maxLongitude, z);
                    int top = MercatorProjection.latitudeToTileY(info.boundingBox.maxLatitude, z);
                    int bottom = MercatorProjection.latitudeToTileY(info.boundingBox.minLatitude, z);
                    for (int x = left; x <= right; x++) for (int y = top; y <= bottom; y++) {
                        var data = map.readMapData(new Tile(x, y, z, 256));
                        assertTrue(data != null, "Unreadable tile after metadata relocation");
                        ways += data.ways.size();
                    }
                }
                assertTrue(ways > 0, "Map lost all ways after metadata patch");
            } finally { map.close(); }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(Files.readAllBytes(result.output())));
            String stem = result.output().getFileName().toString().replace(".map", "");
            assertEquals("MAP_NUM:1\r\nMAP_VER:130\r\n" + stem + ":" + digest + "\r\n",
                    Files.readString(paths.output().resolve("map_md5_list.cfg")));
        }
    }

    @Test
    void generatesARealMapFile() throws Exception {
        Path java = OsmosisCommand.currentJavaExecutable();
        // The test classpath (with test-only osmosis modules) builds the PBF fixture;
        // the pipeline itself runs on the production runtime classpath, as in the packaged app.
        String testClasspath = System.getProperty("java.class.path");
        String classpath = Path.of("target/classes").toAbsolutePath() + File.pathSeparator
                + Files.readString(Path.of("target/runtime-classpath.txt")).strip();
        List<String> log = new CopyOnWriteArrayList<>();
        SystemProcessRunner runner = new SystemProcessRunner();

        Path osm = Files.writeString(dir.resolve("tiny.osm"), OSM);
        Path pbf = dir.resolve("tiny.osm.pbf");
        int convert = runner.run(List.of(java.toString(), "-cp", testClasspath, OsmosisCommand.MAIN_CLASS,
                "--read-xml", "file=" + osm, "--write-pbf", "file=" + pbf), log::add, new CancelToken());
        assertEquals(0, convert, String.join("\n", log));

        Path poly = Files.writeString(dir.resolve("df.poly"),
                PolyFile.fromBoundingBox(new BoundingBox(-16.07, -48.305, -15.48, -47.29), "df"));
        TagFiles tags = TagConfig.resolve(List.of(), dir.resolve("tmp"));
        // Both writer modes: "ram" is the default, "hd" the disk-mode retry.
        Path out = null;
        for (String type : List.of("ram", "hd")) {
            out = dir.resolve("tmp/out-" + type + ".map");
            WriterPlan plan = new WriterPlan(type, 1, 256, 1024, "integration test");
            List<String> cmd = new ArrayList<>(OsmosisCommand.build(java, classpath, plan, dir.resolve("tmp"), pbf, poly, tags, out));
            int exit = runner.run(cmd, log::add, new CancelToken());
            assertEquals(0, exit, String.join("\n", log));
            assertTrue(Files.exists(out), String.join("\n", log));

            BoundingBox box = MapHeaderReader.readBoundingBox(out);
            assertTrue(box.minLat() < -15.79 && box.maxLat() > -15.79, type + ": " + box);
        }

        Path renamed = MapRenamer.rename(out, MapFileName.parse("BR01002303102B83FO00N00E.map").orElseThrow(),
                LocalDate.of(2026, 1, 12), dir.resolve("output"));
        assertTrue(renamed.getFileName().toString().startsWith("BR0100260112"), renamed.toString());
    }
}
