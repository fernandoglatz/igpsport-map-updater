package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import org.junit.jupiter.api.Test;

class MapViewerViewTest {

    @Test
    void paneTitlesShowTheDateFromTheFileName() {
        MapFileName name = MapFileName.parse("BR01002303102B83FO00N00E.map").orElseThrow();

        assertEquals("  ·  2023-03-10", MapViewerView.dateSuffix(name));
    }

    @Test
    void aFileNameWithoutARealDateGetsNoDate() {
        MapFileName name = MapFileName.parse("BR01009999992B83FO00N00E.map").orElseThrow();

        assertEquals("", MapViewerView.dateSuffix(name));
    }
}
