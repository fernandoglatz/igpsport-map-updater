package io.github.fernandoglatz.igpsmap.matching;

import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.sources.GeofabrikRegion;
import io.github.fernandoglatz.igpsmap.sources.PolyCandidate;
import java.net.URI;
import java.util.List;

/**
 * A detected map plus everything needed to regenerate it. {@code pbfUrl} is null only when
 * UNMATCHED; {@code error} is null unless matching hit a problem.
 */
public record MapJob(
        DetectedMap map,
        PolySource poly,
        URI pbfUrl,
        String pbfLabel,
        Confidence confidence,
        double score,
        List<PolyCandidate> polyAlternatives,
        List<GeofabrikRegion> pbfAlternatives,
        String error) {

    public MapJob withPoly(PolySource newPoly) {
        return new MapJob(map, newPoly, pbfUrl, pbfLabel, Confidence.MANUAL, score, polyAlternatives, pbfAlternatives, null);
    }

    public MapJob withPbf(URI newPbfUrl, String newPbfLabel) {
        return new MapJob(map, poly, newPbfUrl, newPbfLabel, Confidence.MANUAL, score, polyAlternatives, pbfAlternatives, null);
    }

    public boolean runnable() {
        return pbfUrl != null && poly != null;
    }
}
