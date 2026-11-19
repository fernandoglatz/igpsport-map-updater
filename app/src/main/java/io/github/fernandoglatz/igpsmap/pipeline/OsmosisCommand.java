package io.github.fernandoglatz.igpsmap.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Builds the osmosis child-JVM command line (Mapsforge writer with the IGPSPORT tag files). */
public final class OsmosisCommand {

    public static final String MAIN_CLASS = "org.openstreetmap.osmosis.core.Osmosis";

    private OsmosisCommand() {
    }

    public static List<String> build(Path javaExe, String classpath, WriterPlan plan, Path tmpDir,
                                     Path pbf, Path poly, TagFiles tags, Path out) {
        return List.of(
                javaExe.toString(), "-Xms" + plan.xmsMb() + "m", "-Xmx" + plan.xmxMb() + "m",
                "-Djava.io.tmpdir=" + tmpDir,
                "-cp", classpath, MAIN_CLASS,
                "--read-pbf-fast", "file=" + pbf,
                "--bounding-polygon", "file=" + poly,
                "--tag-transform", "file=" + tags.transform(),
                "--mapfile-writer", "file=" + out, "type=" + plan.type(),
                "zoom-interval-conf=13,13,13,14,14,14", "threads=" + plan.threads(),
                "tag-conf-file=" + tags.tagConf());
    }

    /** The {@code java} binary of the running runtime (the bundled one when installed). */
    public static Path currentJavaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }
}
