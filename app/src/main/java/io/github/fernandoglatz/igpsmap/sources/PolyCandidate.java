package io.github.fernandoglatz.igpsmap.sources;

import io.github.fernandoglatz.igpsmap.domain.TileBox;
import java.net.URI;
import org.locationtech.jts.geom.Geometry;

/** A polygon file found on openstreetmap.fr, with its zoom-13 tile rectangle. */
public record PolyCandidate(URI url, String name, Geometry geometry, TileBox tileBox) {

    @Override
    public String toString() {
        return name;
    }
}
