package io.github.fernandoglatz.igpsmap.sources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

/** GeoJSON Polygon / MultiPolygon geometry to JTS. */
public final class GeoJson {

    private static final GeometryFactory FACTORY = new GeometryFactory();

    private GeoJson() {
    }

    public static Geometry geometry(JsonObject geometry) {
        String type = geometry.get("type").getAsString();
        JsonArray coordinates = geometry.getAsJsonArray("coordinates");
        return switch (type) {
            case "Polygon" -> polygon(coordinates);
            case "MultiPolygon" -> {
                List<Polygon> polygons = new ArrayList<>();
                coordinates.forEach(p -> polygons.add(polygon(p.getAsJsonArray())));
                yield FACTORY.createMultiPolygon(polygons.toArray(new Polygon[0]));
            }
            default -> throw new IllegalArgumentException("Unsupported geometry type: " + type);
        };
    }

    private static Polygon polygon(JsonArray rings) {
        LinearRing shell = ring(rings.get(0).getAsJsonArray());
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = ring(rings.get(i).getAsJsonArray());
        }
        return FACTORY.createPolygon(shell, holes);
    }

    private static LinearRing ring(JsonArray points) {
        Coordinate[] coords = new Coordinate[points.size()];
        for (int i = 0; i < points.size(); i++) {
            JsonArray p = points.get(i).getAsJsonArray();
            coords[i] = new Coordinate(p.get(0).getAsDouble(), p.get(1).getAsDouble());
        }
        return FACTORY.createLinearRing(coords);
    }
}
