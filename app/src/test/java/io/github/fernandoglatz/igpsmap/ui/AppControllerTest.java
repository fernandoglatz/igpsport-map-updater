package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fernandoglatz.igpsmap.config.AppPaths;
import io.github.fernandoglatz.igpsmap.config.Settings;
import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.io.MapsCsv;
import io.github.fernandoglatz.igpsmap.matching.Confidence;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import io.github.fernandoglatz.igpsmap.matching.PolySource;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppControllerTest {

    @TempDir
    Path dir;

    private static MapJob job(String fileName, PolySource poly, Confidence confidence) {
        return new MapJob(new DetectedMap(Path.of("/dev", fileName), MapFileName.parse(fileName).orElseThrow()), poly,
                URI.create("https://g/brazil-latest.osm.pbf"), "brazil", confidence, 1.0, List.of(), List.of(), null);
    }

    private AppController controller() {
        AppPaths paths = AppPaths.detect(dir, "Linux", dir.resolve("home"), Map.of());
        return new AppController(paths, Settings.defaults(paths), null, null, null);
    }

    @Test
    void confirmWritesRemoteRowsAndSkipsBoundingBoxRows() throws IOException {
        AppController controller = controller();
        controller.confirm(List.of(
                job("BR01002601122B83FO00N00E.map", new PolySource.Remote(URI.create("https://o/df.poly"), "df"), Confidence.HIGH),
                job("BR02002601122833DN04O04Q.map", new PolySource.FromBoundingBox(new BoundingBox(0, 0, 1, 1)), Confidence.FALLBACK)));

        List<MapsCsv.Row> rows = MapsCsv.read(controller.paths().mapsCsv());
        assertEquals(List.of(new MapsCsv.Row("BR01002601122B83FO00N00E.map", "https://g/brazil-latest.osm.pbf", "https://o/df.poly")), rows);
    }

    @Test
    void confirmWithNothingToSaveLeavesNoFile() throws IOException {
        AppController controller = controller();
        controller.confirm(List.of());
        assertEquals(false, Files.exists(controller.paths().mapsCsv()));
    }

    @Test
    void preselectedKeepsCsvManualAndHighOnly() {
        PolySource poly = new PolySource.Remote(URI.create("https://o/x.poly"), "x");
        List<MapJob> jobs = List.of(
                job("BR01002601122B83FO00N00E.map", poly, Confidence.CSV),
                job("BR02002601122B83FO00N00E.map", poly, Confidence.HIGH),
                job("BR03002601122B83FO00N00E.map", poly, Confidence.MEDIUM),
                job("BR04002601122B83FO00N00E.map", poly, Confidence.FALLBACK),
                job("BR05002601122B83FO00N00E.map", poly, Confidence.MANUAL));
        assertEquals(List.of("0100", "0200", "0500"),
                AppController.preselected(jobs).stream().map(j -> j.map().name().regionCode()).toList());
    }
}
