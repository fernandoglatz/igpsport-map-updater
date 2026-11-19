package io.github.fernandoglatz.igpsmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.io.MapHeaderReader;
import io.github.fernandoglatz.igpsmap.io.PolyFile;
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
