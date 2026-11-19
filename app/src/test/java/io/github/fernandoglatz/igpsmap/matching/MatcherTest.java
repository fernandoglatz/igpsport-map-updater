package io.github.fernandoglatz.igpsmap.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.domain.TileBox;
import io.github.fernandoglatz.igpsmap.io.MapsCsv;
import io.github.fernandoglatz.igpsmap.io.PolyFile;
import io.github.fernandoglatz.igpsmap.sources.GeofabrikIndex;
import io.github.fernandoglatz.igpsmap.sources.GeofabrikRegion;
import io.github.fernandoglatz.igpsmap.sources.PolyCandidate;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

class MatcherTest {

    private static GeofabrikIndex index;

    /** Distrito Federal tiles from the real map: 2B83FO00N00E. */
    private static final String DF_GEOCODE = "2B83FO00N00E";
    private static final DetectedMap DF_MAP = detected("BR01002601122B83FO00N00E.map");

    @BeforeAll
    static void loadIndex() throws IOException {
        try (InputStream in = MatcherTest.class.getResourceAsStream("/geofabrik-mini.json")) {
            index = GeofabrikIndex.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static DetectedMap detected(String fileName) {
        return new DetectedMap(Path.of("/dev/" + fileName), MapFileName.parse(fileName).orElseThrow());
    }

    /** A candidate whose bounding box sits strictly inside the given tiles, shifted by dx tiles. */
    private static PolyCandidate candidate(String name, TileBox tiles, int dx) {
        TileBox shifted = new TileBox(tiles.xStart() + dx, tiles.yStart(), tiles.xSpan(), tiles.ySpan());
        BoundingBox outer = shifted.toBoundingBox();
        double lonPad = 360.0 / TileBox.TILES / 4;
        double latPad = (outer.maxLat() - outer.minLat()) / shifted.ySpan() / 4;
        BoundingBox inner = new BoundingBox(outer.minLat() + latPad, outer.minLon() + lonPad,
                outer.maxLat() - latPad, outer.maxLon() - lonPad);
        Geometry geometry = PolyFile.parse(PolyFile.fromBoundingBox(inner, name));
        return new PolyCandidate(URI.create("https://osm.fr/" + name + ".poly"), name, geometry,
                GeoCode.toTileBox(inner));
    }

    private static Matcher matcher(List<PolyCandidate> candidates, List<MapsCsv.Row> csv) {
        return new Matcher(index, country -> candidates, csv);
    }

    @Test
    void csvRowWinsIgnoringDate() {
        AtomicInteger calls = new AtomicInteger();
        MapsCsv.Row row = new MapsCsv.Row("BR01002303102B83FO00N00E.map", "https://g/brazil-latest.osm.pbf", "https://o/df.poly");
        MapJob job = new Matcher(index, c -> {
            calls.incrementAndGet();
            return List.of();
        }, List.of(row)).match(DF_MAP);

        assertEquals(Confidence.CSV, job.confidence());
        assertEquals(URI.create("https://g/brazil-latest.osm.pbf"), job.pbfUrl());
        assertEquals(new PolySource.Remote(URI.create("https://o/df.poly"), "df.poly"), job.poly());
        assertEquals(0, calls.get());
    }

    @Test
    void exactGeoCodeIsHighConfidence() {
        TileBox df = GeoCode.decode(DF_GEOCODE);
        MapJob job = matcher(List.of(candidate("goias", new TileBox(2800, 4400, 100, 100), 0),
                candidate("distrito-federal", df, 0)), List.of()).match(DF_MAP);

        assertEquals(Confidence.HIGH, job.confidence());
        assertEquals(1.0, job.score(), 1e-9);
        assertEquals("distrito-federal", job.poly().label());
        assertNull(job.error());
    }

    @Test
    void partialOverlapPicksBestAndSortsAlternatives() {
        TileBox df = GeoCode.decode(DF_GEOCODE);
        List<PolyCandidate> candidates = new ArrayList<>();
        for (int dx = 1; dx <= 7; dx++) {
            candidates.add(candidate("shift" + dx, df, dx));
        }
        MapJob job = matcher(candidates, List.of()).match(DF_MAP);

        assertEquals("shift1", job.poly().label());
        assertEquals(Matcher.score(df, candidates.get(0).tileBox()), job.score(), 1e-9);
        assertEquals(Confidence.fromScore(job.score()), job.confidence());
        assertEquals(5, job.polyAlternatives().size());
        assertEquals(List.of("shift1", "shift2", "shift3", "shift4", "shift5"),
                job.polyAlternatives().stream().map(PolyCandidate::name).toList());
    }

    @Test
    void noGoodCandidateFallsBackToBoundingBox() {
        TileBox df = GeoCode.decode(DF_GEOCODE);
        MapJob job = matcher(List.of(candidate("far", df, 20)), List.of()).match(DF_MAP);

        assertEquals(Confidence.FALLBACK, job.confidence());
        PolySource.FromBoundingBox poly = assertInstanceOf(PolySource.FromBoundingBox.class, job.poly());
        assertEquals(df.toBoundingBox(), poly.box());
        assertTrue(job.polyAlternatives().stream().anyMatch(c -> c.name().equals("far")));
        assertEquals(URI.create("https://download.geofabrik.de/south-america/brazil/centro-oeste-latest.osm.pbf"), job.pbfUrl());
    }

    @Test
    void unknownCountryIsUnmatched() {
        MapJob job = matcher(List.of(), List.of()).match(detected("ZZ01002601122B83FO00N00E.map"));
        assertEquals(Confidence.UNMATCHED, job.confidence());
        assertTrue(job.error().contains("ZZ"));
        assertNull(job.pbfUrl());
        assertFalse(Confidence.UNMATCHED.preselected());
    }

    @Test
    void candidateSourceFailureFallsBackWithError() {
        MapJob job = new Matcher(index, c -> {
            throw new IOException("osm.fr down");
        }, List.of()).match(DF_MAP);
        assertEquals(Confidence.FALLBACK, job.confidence());
        assertTrue(job.error().contains("osm.fr down"));
    }

    @Test
    void manualEditsArePreselected() {
        MapJob job = matcher(List.of(), List.of()).match(DF_MAP)
                .withPoly(new PolySource.Remote(URI.create("https://o/x.poly"), "x"))
                .withPbf(URI.create("https://g/sul-latest.osm.pbf"), "sul");
        assertEquals(Confidence.MANUAL, job.confidence());
        assertEquals("sul", job.pbfLabel());
        assertTrue(job.confidence().preselected());
        assertNull(job.error());
    }

    @Test
    void geofabrikOutlinesAreCandidatesWhenOsmFrHasNothing() {
        // The "sul" fixture rectangle (-58..-48, -34..-22) as an IGPSPORT file.
        GeofabrikRegion sul = index.subtree("brazil").stream().filter(r -> r.id().equals("sul")).findFirst().orElseThrow();
        String geo = GeoCode.encode(PolyFile.boundingBox(sul.geometry()));
        MapJob job = new Matcher(index, c -> {
            throw new IOException("not on osm.fr");
        }, List.of()).match(detected("BR2100260112" + geo + ".map"));

        assertEquals(Confidence.HIGH, job.confidence());
        assertEquals(new PolySource.Remote(
                URI.create("https://download.geofabrik.de/south-america/brazil/sul.poly"), "sul"), job.poly());
        assertNull(job.error());
    }

    @Test
    void osmFrCandidateWinsTiesWithGeofabrik() {
        GeofabrikRegion sul = index.subtree("brazil").stream().filter(r -> r.id().equals("sul")).findFirst().orElseThrow();
        TileBox tiles = GeoCode.toTileBox(PolyFile.boundingBox(sul.geometry()));
        PolyCandidate osmFr = new PolyCandidate(URI.create("https://osm.fr/south.poly"), "south", sul.geometry(), tiles);
        MapJob job = matcher(List.of(osmFr), List.of()).match(detected("BR2100260112" + GeoCode.encode(tiles) + ".map"));
        assertEquals("south", job.poly().label());
    }

    @Test
    void osmBoundaryIsUsedWhenOtherSourcesAreWeak() {
        TileBox df = GeoCode.decode(DF_GEOCODE);
        AtomicInteger calls = new AtomicInteger();
        PolyCandidate boundary = candidate("distrito-federal-osm", df, 0);
        MapJob job = new Matcher(index, c -> List.of(candidate("far", df, 20)), cc -> {
            calls.incrementAndGet();
            assertEquals("BR", cc);
            return List.of(boundary);
        }, List.of()).match(DF_MAP);

        assertEquals(Confidence.HIGH, job.confidence());
        assertEquals("distrito-federal-osm", job.poly().label());
        assertEquals(1, calls.get());
        assertNull(job.error());
    }

    /** A boundary whose tiles match the target but whose rough geometry straddles sudeste and sul. */
    private static PolyCandidate roughBoundary(TileBox target) {
        Geometry rough = PolyFile.parse(PolyFile.fromBoundingBox(new BoundingBox(-26, -50, -20, -45), "rough"));
        return new PolyCandidate(URI.create("https://polygons.example/get_poly.py?id=7&params=0"), "Somewhere (OSM)", rough, target);
    }

    @Test
    void pbfForAnOsmBoundaryUsesItsRealPolygon() {
        BoundingBox insideSudeste = new BoundingBox(-23, -46, -22, -45);
        TileBox target = GeoCode.toTileBox(insideSudeste);
        Geometry real = PolyFile.parse(PolyFile.fromBoundingBox(insideSudeste, "real"));
        Matcher.BoundarySource source = new Matcher.BoundarySource() {
            @Override
            public List<PolyCandidate> forCountry(String countryCode) {
                return List.of(roughBoundary(target));
            }

            @Override
            public Geometry geometry(PolyCandidate candidate) {
                return real;
            }
        };
        MapJob job = new Matcher(index, c -> List.of(), source, List.of())
                .match(detected("BR2400260112" + GeoCode.encode(target) + ".map"));

        assertEquals("Somewhere (OSM)", job.poly().label());
        assertEquals("sudeste", job.pbfLabel());
    }

    @Test
    void pbfFallsBackToTheRoughGeometryWhenTheRealPolygonFails() {
        TileBox target = GeoCode.toTileBox(new BoundingBox(-23, -46, -22, -45));
        Matcher.BoundarySource source = new Matcher.BoundarySource() {
            @Override
            public List<PolyCandidate> forCountry(String countryCode) {
                return List.of(roughBoundary(target));
            }

            @Override
            public Geometry geometry(PolyCandidate candidate) throws IOException {
                throw new IOException("polygons site down");
            }
        };
        MapJob job = new Matcher(index, c -> List.of(), source, List.of())
                .match(detected("BR2400260112" + GeoCode.encode(target) + ".map"));
        assertEquals(Confidence.HIGH, job.confidence());
        assertEquals("brazil", job.pbfLabel().toLowerCase());
    }

    @Test
    void osmBoundariesAreNotQueriedWhenAMatchIsAlreadyHigh() {
        AtomicInteger calls = new AtomicInteger();
        MapJob job = new Matcher(index, c -> List.of(candidate("distrito-federal", GeoCode.decode(DF_GEOCODE), 0)), cc -> {
            calls.incrementAndGet();
            return List.of();
        }, List.of()).match(DF_MAP);
        assertEquals(Confidence.HIGH, job.confidence());
        assertEquals(0, calls.get());
    }

    @Test
    void osmBoundaryFailureKeepsTheFallbackWithAnError() {
        TileBox df = GeoCode.decode(DF_GEOCODE);
        MapJob job = new Matcher(index, c -> List.of(candidate("far", df, 20)), cc -> {
            throw new IOException("overpass busy");
        }, List.of()).match(DF_MAP);
        assertEquals(Confidence.FALLBACK, job.confidence());
        assertTrue(job.error().contains("overpass busy"), job.error());
    }

    @Test
    void confidenceBands() {
        assertEquals(Confidence.HIGH, Confidence.fromScore(0.9));
        assertEquals(Confidence.MEDIUM, Confidence.fromScore(0.6));
        assertEquals(Confidence.LOW, Confidence.fromScore(0.59));
        assertTrue(Confidence.CSV.preselected());
        assertTrue(Confidence.HIGH.preselected());
        assertFalse(Confidence.MEDIUM.preselected());
    }

    @Test
    void pbfIsSmallestRegionCoveringPolygon() {
        List<GeofabrikRegion> subtree = index.subtree("brazil");
        GeofabrikRegion brazil = index.country("BR").orElseThrow();
        Geometry insideSudeste = PolyFile.parse(PolyFile.fromBoundingBox(new BoundingBox(-23, -47, -22, -46), "a"));
        Geometry straddling = PolyFile.parse(PolyFile.fromBoundingBox(new BoundingBox(-26, -50, -20, -45), "b"));

        assertEquals("sudeste", Matcher.choosePbfRegion(subtree, insideSudeste, brazil).id());
        assertEquals("brazil", Matcher.choosePbfRegion(subtree, straddling, brazil).id());
    }

    @Test
    void highMatchUsesSmallestCoveringPbf() {
        // Distrito Federal sits inside the centro-oeste fixture rectangle.
        MapJob job = matcher(List.of(candidate("distrito-federal", GeoCode.decode(DF_GEOCODE), 0)), List.of()).match(DF_MAP);
        assertEquals("centro-oeste", job.pbfLabel());
        assertEquals(4, job.pbfAlternatives().size());
    }
}
