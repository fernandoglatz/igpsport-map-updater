package io.github.fernandoglatz.igpsmap.matching;

import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.domain.TileBox;
import io.github.fernandoglatz.igpsmap.io.MapsCsv;
import io.github.fernandoglatz.igpsmap.io.PolyFile;
import io.github.fernandoglatz.igpsmap.sources.GeofabrikIndex;
import io.github.fernandoglatz.igpsmap.sources.GeofabrikRegion;
import io.github.fernandoglatz.igpsmap.sources.PolyCandidate;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.locationtech.jts.geom.Geometry;

/** Matches a detected IGPSPORT map to a polygon and a PBF extract (spec section 2). */
public final class Matcher {

    /** Tolerance in degrees when testing whether a region covers a polygon. */
    private static final double COVER_BUFFER = 0.05;
    private static final int MAX_ALTERNATIVES = 5;

    @FunctionalInterface
    public interface PolyCandidateSource {
        List<PolyCandidate> forCountry(GeofabrikRegion country) throws IOException;
    }

    /** OpenStreetMap administrative boundaries for a country, by ISO 3166-1 alpha-2 code. */
    @FunctionalInterface
    public interface BoundarySource {
        List<PolyCandidate> forCountry(String countryCode) throws IOException;

        /** The boundary's real outline; candidates only carry its bounding rectangle. */
        default Geometry geometry(PolyCandidate candidate) throws IOException {
            return candidate.geometry();
        }
    }

    private final GeofabrikIndex index;
    private final PolyCandidateSource candidates;
    private final BoundarySource boundaries;
    private final List<MapsCsv.Row> csvRows;

    public Matcher(GeofabrikIndex index, PolyCandidateSource candidates, List<MapsCsv.Row> csvRows) {
        this(index, candidates, countryCode -> List.of(), csvRows);
    }

    public Matcher(GeofabrikIndex index, PolyCandidateSource candidates, BoundarySource boundaries,
                   List<MapsCsv.Row> csvRows) {
        this.index = index;
        this.candidates = candidates;
        this.boundaries = boundaries;
        this.csvRows = List.copyOf(csvRows);
    }

    public MapJob match(DetectedMap map) {
        MapFileName name = map.name();
        Optional<MapsCsv.Row> row = MapsCsv.findByRegionKey(csvRows, name.regionKey());
        if (row.isPresent()) {
            return fromCsv(map, row.get());
        }

        // iGPSPORT uses SL for Slovakia; OSM sources use the ISO code SK.
        // Preserve the vendor code in filenames and region identities.
        String isoCountryCode = "SL".equals(name.countryCode()) ? "SK" : name.countryCode();
        Optional<GeofabrikRegion> country = index.country(isoCountryCode);
        if (country.isEmpty()) {
            return new MapJob(map, null, null, null, Confidence.UNMATCHED, 0, List.of(), List.of(),
                    "No Geofabrik extract for country code " + name.countryCode());
        }
        List<GeofabrikRegion> subtree = index.subtree(country.get().id());
        List<GeofabrikRegion> pbfAlternatives = subtree.stream()
                .sorted(Comparator.comparingDouble(GeofabrikRegion::area)).toList();
        TileBox target = GeoCode.decode(name.geoCode());

        // openstreetmap.fr polygons first, so they win ties; Geofabrik outlines cover whole
        // countries/regions and countries that openstreetmap.fr does not have.
        List<PolyCandidate> all = new ArrayList<>();
        String error = null;
        try {
            all.addAll(candidates.forCountry(country.get()));
        } catch (IOException | RuntimeException e) {
            error = "Polygon lookup failed: " + e.getMessage();
        }
        subtree.forEach(region -> all.add(geofabrikCandidate(region)));
        List<PolyCandidate> ranked = rank(all, target);

        // Last resort, only when nothing matched well: OSM administrative boundaries (one
        // Overpass query per country), e.g. Japanese prefectures that openstreetmap.fr lacks.
        java.util.Set<PolyCandidate> boundaryCandidates = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (ranked.isEmpty() || Confidence.fromScore(score(target, ranked.get(0).tileBox())) != Confidence.HIGH) {
            try {
                List<PolyCandidate> found = boundaries.forCountry(isoCountryCode);
                boundaryCandidates.addAll(found);
                all.addAll(found);
                ranked = rank(all, target);
            } catch (IOException | RuntimeException e) {
                String message = "OSM boundary lookup failed: " + e.getMessage();
                error = error == null ? message : error + "; " + message;
            }
        }

        double best = ranked.isEmpty() ? 0 : score(target, ranked.get(0).tileBox());
        Confidence confidence = Confidence.fromScore(best);
        PolySource poly;
        Geometry polyGeometry;
        if (confidence == Confidence.LOW) {
            confidence = Confidence.FALLBACK;
            poly = new PolySource.FromBoundingBox(target.toBoundingBox());
            polyGeometry = PolyFile.parse(PolyFile.fromBoundingBox(target.toBoundingBox(), "bbox"));
        } else {
            PolyCandidate chosen = ranked.get(0);
            poly = new PolySource.Remote(chosen.url(), chosen.name());
            polyGeometry = chosen.geometry();
            if (boundaryCandidates.contains(chosen)) {
                try {
                    polyGeometry = boundaries.geometry(chosen); // real outline gives a tighter PBF choice
                } catch (IOException | RuntimeException e) {
                    // keep the bounding rectangle: the PBF may just be larger than necessary
                }
            }
            error = null; // a usable polygon was found despite a partial lookup failure
        }
        GeofabrikRegion pbf = choosePbfRegion(subtree, polyGeometry, country.get());
        return new MapJob(map, poly, pbf.pbfUrl(), pbf.name(), confidence, best, ranked, pbfAlternatives, error);
    }

    private static List<PolyCandidate> rank(List<PolyCandidate> all, TileBox target) {
        return all.stream()
                .filter(c -> score(target, c.tileBox()) > 0)
                .sorted(Comparator.comparingDouble((PolyCandidate c) -> score(target, c.tileBox())).reversed())
                .limit(MAX_ALTERNATIVES)
                .toList();
    }

    /** 1.0 when the tile rectangles are identical (same GEOCODE), otherwise their IoU. */
    public static double score(TileBox target, TileBox candidate) {
        return target.equals(candidate) ? 1.0 : target.iou(candidate);
    }

    /** The smallest region whose (slightly buffered) outline covers the polygon; else the country. */
    public static GeofabrikRegion choosePbfRegion(List<GeofabrikRegion> subtree, Geometry poly, GeofabrikRegion country) {
        return subtree.stream()
                .filter(r -> r.geometry().buffer(COVER_BUFFER).covers(poly))
                .min(Comparator.comparingDouble(GeofabrikRegion::area))
                .orElse(country);
    }

    /** A Geofabrik region outline as a candidate; Geofabrik serves it next to the PBF as .poly. */
    static PolyCandidate geofabrikCandidate(GeofabrikRegion region) {
        URI url = URI.create(region.pbfUrl().toString().replaceFirst("-latest\\.osm\\.pbf$", ".poly"));
        return new PolyCandidate(url, region.id(), region.geometry(),
                GeoCode.toTileBox(PolyFile.boundingBox(region.geometry())));
    }

    private static MapJob fromCsv(DetectedMap map, MapsCsv.Row row) {
        URI pbf = URI.create(row.pbfUrl());
        URI poly = URI.create(row.polyUrl());
        return new MapJob(map, new PolySource.Remote(poly, lastSegment(poly)), pbf, lastSegment(pbf),
                Confidence.CSV, 1.0, List.of(), List.of(), null);
    }

    private static String lastSegment(URI uri) {
        String path = uri.getPath();
        return path == null ? uri.toString() : path.substring(path.lastIndexOf('/') + 1);
    }
}
