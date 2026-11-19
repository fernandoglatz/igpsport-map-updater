package io.github.fernandoglatz.igpsmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OsmosisCommandTest {

    @Test
    void buildsTheSameArgumentsAsScriptSh() {
        // Paths are built per OS (Windows uses backslashes), so expectations use Path.toString().
        Path java = Path.of("/jre/bin/java");
        Path tmp = Path.of("/w/tmp");
        Path pbf = Path.of("/w/download/brazil-latest.osm.pbf");
        Path poly = Path.of("/w/download/df.poly");
        TagFiles tags = new TagFiles(Path.of("/w/tmp/tags/tag-igpsport.xml"), Path.of("/w/tmp/tags/tag-igpsport-transform.xml"));
        Path out = Path.of("/w/tmp/out_BR0100.map");

        WriterPlan plan = new WriterPlan("hd", 4, 2048, 8192, "test");
        List<String> cmd = OsmosisCommand.build(java, "a.jar:b.jar", plan, tmp, pbf, poly, tags, out);

        assertEquals(List.of(
                java.toString(), "-Xms2048m", "-Xmx8192m", "-Djava.io.tmpdir=" + tmp,
                "-cp", "a.jar:b.jar", "org.openstreetmap.osmosis.core.Osmosis",
                "--read-pbf-fast", "file=" + pbf,
                "--bounding-polygon", "file=" + poly,
                "--tag-transform", "file=" + tags.transform(),
                "--mapfile-writer", "file=" + out, "type=hd",
                "zoom-interval-conf=13,13,13,14,14,14", "threads=4",
                "tag-conf-file=" + tags.tagConf()), cmd);
    }

    @Test
    void usesTheWriterTypeAndThreadsOfThePlan() {
        TagFiles tags = new TagFiles(Path.of("t.xml"), Path.of("tt.xml"));
        List<String> cmd = OsmosisCommand.build(Path.of("java"), "cp", new WriterPlan("ram", 2, 3072, 13107, "auto"),
                Path.of("tmp"), Path.of("a.pbf"), Path.of("a.poly"), tags, Path.of("o.map"));
        assertTrue(cmd.contains("type=ram"), cmd.toString());
        assertTrue(cmd.contains("threads=2"), cmd.toString());
        assertTrue(cmd.contains("-Xms3072m") && cmd.contains("-Xmx13107m"), cmd.toString());
    }

    @Test
    void currentJavaExecutableExists() {
        assertTrue(Files.isExecutable(OsmosisCommand.currentJavaExecutable()));
    }
}
