package io.github.fernandoglatz.igpsmap.sources;

import java.net.URI;
import java.util.List;
import org.locationtech.jts.geom.Geometry;

/** One extract from the Geofabrik download index. {@code parent} is null for top-level regions. */
public record GeofabrikRegion(String id, String parent, String name, List<String> iso2, URI pbfUrl, Geometry geometry) {

    public double area() {
        return geometry.getArea();
    }

    @Override
    public String toString() {
        return name;
    }
}
