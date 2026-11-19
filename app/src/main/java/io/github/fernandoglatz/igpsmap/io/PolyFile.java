package io.github.fernandoglatz.igpsmap.io;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

/**
 * Osmosis polygon filter file format: a name line, then sections (an id line, {@code lon lat}
 * lines, {@code END}), then a final {@code END}. Sections whose id starts with {@code !} are holes.
 */
public final class PolyFile {

    private static final GeometryFactory FACTORY = new GeometryFactory();

    private PolyFile() {
    }

    public static Geometry parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Empty polygon file");
        }
        String[] lines = text.split("\\R");
        List<Polygon> shells = new ArrayList<>();
        List<Polygon> holes = new ArrayList<>();
        int i = 1; // line 0 is the polygon name
        boolean finished = false;
        while (i < lines.length) {
            String id = lines[i++].trim();
            if (id.isEmpty()) {
                continue;
            }
            if (id.equals("END")) {
                finished = true;
                break;
            }
            List<Coordinate> ring = new ArrayList<>();
            boolean sectionClosed = false;
            while (i < lines.length) {
                String line = lines[i++].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equals("END")) {
                    sectionClosed = true;
                    break;
                }
                ring.add(parseCoordinate(line));
            }
            if (!sectionClosed) {
                throw new IllegalArgumentException("Polygon section '" + id + "' has no END");
            }
            Polygon polygon = toPolygon(ring);
            if (polygon != null) {
                (id.startsWith("!") ? holes : shells).add(polygon);
            }
        }
        if (!finished) {
            throw new IllegalArgumentException("Polygon file has no final END");
        }
        if (shells.isEmpty()) {
            throw new IllegalArgumentException("Polygon file has no outer ring");
        }
        Geometry result = union(shells);
        if (!holes.isEmpty()) {
            result = result.difference(union(holes));
        }
        return result;
    }

    public static String fromBoundingBox(BoundingBox box, String name) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('\n').append("1\n");
        appendPoint(sb, box.minLon(), box.minLat());
        appendPoint(sb, box.maxLon(), box.minLat());
        appendPoint(sb, box.maxLon(), box.maxLat());
        appendPoint(sb, box.minLon(), box.maxLat());
        appendPoint(sb, box.minLon(), box.minLat());
        sb.append("END\nEND\n");
        return sb.toString();
    }

    public static BoundingBox boundingBox(Geometry geometry) {
        Envelope e = geometry.getEnvelopeInternal();
        return new BoundingBox(e.getMinY(), e.getMinX(), e.getMaxY(), e.getMaxX());
    }

    private static Coordinate parseCoordinate(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid coordinate line: " + line);
        }
        try {
            return new Coordinate(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid coordinate line: " + line, e);
        }
    }

    private static Polygon toPolygon(List<Coordinate> ring) {
        if (ring.size() < 3) {
            return null;
        }
        List<Coordinate> closed = new ArrayList<>(ring);
        if (!closed.get(0).equals2D(closed.get(closed.size() - 1))) {
            closed.add(new Coordinate(closed.get(0)));
        }
        if (closed.size() < 4) {
            return null;
        }
        Polygon polygon = FACTORY.createPolygon(closed.toArray(new Coordinate[0]));
        return polygon.isValid() ? polygon : (Polygon) largestPolygon(polygon.buffer(0));
    }

    private static Geometry largestPolygon(Geometry geometry) {
        Geometry best = null;
        for (int n = 0; n < geometry.getNumGeometries(); n++) {
            Geometry part = geometry.getGeometryN(n);
            if (part instanceof Polygon && (best == null || part.getArea() > best.getArea())) {
                best = part;
            }
        }
        return best != null ? best : FACTORY.createPolygon();
    }

    private static Geometry union(List<Polygon> polygons) {
        return FACTORY.createMultiPolygon(polygons.toArray(new Polygon[0])).union();
    }

    private static void appendPoint(StringBuilder sb, double lon, double lat) {
        sb.append(String.format(Locale.ROOT, "   %.7f   %.7f%n", lon, lat).replace(System.lineSeparator(), "\n"));
    }
}
