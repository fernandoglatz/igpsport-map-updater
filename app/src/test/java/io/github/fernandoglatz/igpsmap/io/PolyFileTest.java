package io.github.fernandoglatz.igpsmap.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

class PolyFileTest {

    private static final String SQUARE = """
            square
            1
               0.0E+00   0.0E+00
               1.0E+01   0.0E+00
               1.0E+01   1.0E+01
               0.0E+00   1.0E+01
               0.0E+00   0.0E+00
            END
            END
            """;

    @Test
    void parsesSingleRing() {
        Geometry g = PolyFile.parse(SQUARE);
        assertEquals(new BoundingBox(0, 0, 10, 10), PolyFile.boundingBox(g));
        assertEquals(100.0, g.getArea(), 1e-9);
    }

    @Test
    void parsesMultipleRingsAsMultiPolygon() {
        String text = """
                two
                1
                0 0
                1 0
                1 1
                0 0
                END
                2
                5 5
                6 5
                6 6
                5 5
                END
                END
                """;
        Geometry g = PolyFile.parse(text);
        assertEquals(2, g.getNumGeometries());
        assertEquals(new BoundingBox(0, 0, 6, 6), PolyFile.boundingBox(g));
    }

    @Test
    void exclamationRingIsHole() {
        String text = SQUARE.replace("END\nEND\n", """
                END
                !hole
                2 2
                4 2
                4 4
                2 4
                END
                END
                """);
        assertEquals(96.0, PolyFile.parse(text).getArea(), 1e-9);
    }

    @Test
    void closesUnclosedRing() {
        String text = "open\n1\n0 0\n2 0\n2 2\nEND\nEND\n";
        assertEquals(2.0, PolyFile.parse(text).getArea(), 1e-9);
    }

    @Test
    void parsesScientificNotationAndTabs() {
        Geometry g = PolyFile.parse("sci\n1\n\t-4.83E+01\t-1.6E+01\n-4.73E+01 -1.6E+01\n-4.73E+01 -1.5E+01\nEND\nEND\n");
        assertEquals(-48.3, PolyFile.boundingBox(g).minLon(), 1e-9);
    }

    @Test
    void rejectsMissingEnd() {
        assertThrows(IllegalArgumentException.class, () -> PolyFile.parse("x\n1\n0 0\n1 0\n1 1\n"));
    }

    @Test
    void rejectsEmptyOrGarbage() {
        assertThrows(IllegalArgumentException.class, () -> PolyFile.parse(""));
        assertThrows(IllegalArgumentException.class, () -> PolyFile.parse("<html>404 Not Found</html>"));
        assertThrows(IllegalArgumentException.class, () -> PolyFile.parse("x\n1\n0 zero\n1 0\n1 1\nEND\nEND\n"));
    }

    @Test
    void boundingBoxPolyRoundTrips() {
        BoundingBox box = new BoundingBox(-16.07, -48.305, -15.48, -47.29);
        String text = PolyFile.fromBoundingBox(box, "bbox-BR0100");
        assertTrue(text.startsWith("bbox-BR0100\n"));
        assertEquals(box, PolyFile.boundingBox(PolyFile.parse(text)));
    }
}
