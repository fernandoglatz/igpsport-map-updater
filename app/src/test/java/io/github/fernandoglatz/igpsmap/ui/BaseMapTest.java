package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class BaseMapTest {

    private static final String FIXTURE = """
            {"type":"FeatureCollection","features":[
             {"type":"Feature","properties":{"name":"Squareland","label_x":0.5,"label_y":0.5,"min_label":3},
              "geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}},
             {"type":"Feature","properties":{},
              "geometry":{"type":"MultiPolygon","coordinates":[[[[5,5],[6,5],[6,6],[5,5]]],[[[7,7],[8,7],[8,8],[7,7]]]]}}
            ]}
            """;

    @Test
    void parsesCountriesWithLabels() throws IOException {
        List<BaseMap.Country> countries = BaseMap.parseCountries(
                new ByteArrayInputStream(FIXTURE.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, countries.size());
        BaseMap.Country square = countries.get(0);
        assertEquals("Squareland", square.name());
        assertEquals(1.0, square.geometry().getArea(), 1e-9);
        assertEquals(0.5, square.labelLon(), 1e-9);
        assertEquals(3.0, square.minLabelZoom(), 1e-9);
        assertEquals("", countries.get(1).name());
        assertEquals(2, countries.get(1).geometry().getNumGeometries());
    }

    @Test
    void bundledDataHasCoarseAndDetailedLevels() {
        BaseMap map = BaseMap.bundled();
        int coarse = map.countries(Viewport.MIN_SCALE).size();
        int detailed = map.countries(BaseMap.DETAIL_SCALE).size();
        assertTrue(coarse >= 170, "coarse: " + coarse);
        assertTrue(detailed >= 240, "detailed: " + detailed);
        assertTrue(map.countries(BaseMap.DETAIL_SCALE).stream().anyMatch(c -> c.name().equals("Japan")));
    }

    @Test
    void lakesAppearOnlyWhenZoomedIn() {
        BaseMap map = BaseMap.bundled();
        assertTrue(map.lakes(Viewport.MIN_SCALE).isEmpty());
        assertTrue(map.lakes(BaseMap.DETAIL_SCALE).size() > 100);
    }

    @Test
    void labelsFollowNaturalEarthMinimumZoom() {
        BaseMap.Country japan = BaseMap.bundled().countries(BaseMap.DETAIL_SCALE).stream()
                .filter(c -> c.name().equals("Japan")).findFirst().orElseThrow();
        // Japan's min_label is 1.7: hidden at zoom 1 (scale 512), shown at zoom 2 (scale 1024).
        assertFalse(BaseMap.labelVisible(japan, 512));
        assertTrue(BaseMap.labelVisible(japan, 1024));
    }
}
