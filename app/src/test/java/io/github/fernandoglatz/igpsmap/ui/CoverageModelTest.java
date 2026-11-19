package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.matching.Confidence;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import io.github.fernandoglatz.igpsmap.matching.PolySource;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoverageModelTest {

    /** DF and Goiás from maps.csv; Goiás surrounds Distrito Federal. */
    private static final String DF = "BR01002303102B83FO00N00E.map";
    private static final String GO = "BR02002303102833DN04O04Q.map";
    private static final String JP = "JP01002303105K52HS00S00X.map";

    private static MapJob job(String file, Confidence confidence) {
        boolean runnable = confidence != Confidence.UNMATCHED;
        return new MapJob(new DetectedMap(Path.of(file), MapFileName.parse(file).orElseThrow()),
                runnable ? new PolySource.Remote(URI.create("https://o/x.poly"), "x") : null,
                runnable ? URI.create("https://g/x.pbf") : null, "x", confidence, 1, List.of(), List.of(), null);
    }

    @Test
    void eachMapCoversItsGeocodeRectangle() {
        CoverageModel model = CoverageModel.of(List.of(job(DF, Confidence.HIGH)), Set.of());
        assertEquals(GeoCode.decode("2B83FO00N00E").toBoundingBox(), model.items().get(0).box());
    }

    @Test
    void statusGroupsConfidences() {
        assertEquals(CoverageModel.Status.OK, CoverageModel.Status.of(Confidence.CSV));
        assertEquals(CoverageModel.Status.OK, CoverageModel.Status.of(Confidence.MANUAL));
        assertEquals(CoverageModel.Status.OK, CoverageModel.Status.of(Confidence.HIGH));
        assertEquals(CoverageModel.Status.PARTIAL, CoverageModel.Status.of(Confidence.MEDIUM));
        assertEquals(CoverageModel.Status.PARTIAL, CoverageModel.Status.of(Confidence.LOW));
        assertEquals(CoverageModel.Status.BOUNDING_BOX, CoverageModel.Status.of(Confidence.FALLBACK));
        assertEquals(CoverageModel.Status.UNMATCHED, CoverageModel.Status.of(Confidence.UNMATCHED));
    }

    @Test
    void summaryCountsMapsCountriesAndStatuses() {
        CoverageModel model = CoverageModel.of(List.of(job(DF, Confidence.HIGH), job(GO, Confidence.MEDIUM),
                job(JP, Confidence.FALLBACK)), Set.of());
        assertEquals("3 maps · 2 countries · 1 matched · 1 partial · 1 bounding box", model.summary());
    }

    @Test
    void summaryLeavesOutEmptyStatuses() {
        assertEquals("1 map · 1 country · 1 matched",
                CoverageModel.of(List.of(job(DF, Confidence.CSV)), Set.of()).summary());
    }

    @Test
    void hitTestReturnsTheSmallestMapUnderThePoint() {
        CoverageModel model = CoverageModel.of(List.of(job(GO, Confidence.HIGH), job(DF, Confidence.HIGH)), Set.of());
        // Brasília is inside both rectangles: the smaller one (DF) wins.
        assertEquals(DF, model.itemAt(-47.88, -15.79).orElseThrow().job().map().path().toString());
        // Inside Goiás only.
        assertEquals(GO, model.itemAt(-50.0, -16.0).orElseThrow().job().map().path().toString());
        assertTrue(model.itemAt(0, 0).isEmpty());
    }

    @Test
    void extentCoversEveryMap() {
        CoverageModel model = CoverageModel.of(List.of(job(DF, Confidence.HIGH), job(JP, Confidence.HIGH)), Set.of());
        BoundingBox e = model.extent().orElseThrow();
        assertTrue(e.minLon() < -48 && e.maxLon() > 136 && e.minLat() < -16 && e.maxLat() > 34, e.toString());
        assertTrue(CoverageModel.of(List.of(), Set.of()).extent().isEmpty());
    }

    @Test
    void remembersWhichMapsAreSelected() {
        MapJob df = job(DF, Confidence.HIGH);
        MapJob go = job(GO, Confidence.HIGH);
        CoverageModel model = CoverageModel.of(List.of(df, go), Set.of(go));
        assertEquals(List.of(false, true), model.items().stream().map(CoverageModel.Item::selected).toList());
    }
}
