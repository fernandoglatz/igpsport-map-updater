package io.github.fernandoglatz.igpsmap.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MapFileNameTest {

    @Test
    void parsesValidName() {
        assertEquals(Optional.of(new MapFileName("BR", "0100", "230310", "2B83FO00N00E")),
                MapFileName.parse("BR01002303102B83FO00N00E.map"));
    }

    @Test
    void regionKeyIgnoresDate() {
        assertEquals("BR01002B83FO00N00E",
                MapFileName.parse("BR01002601122B83FO00N00E.map").orElseThrow().regionKey());
    }

    @Test
    void rebuildsFileName() {
        assertEquals("BR01002303102B83FO00N00E.map",
                new MapFileName("BR", "0100", "230310", "2B83FO00N00E").toFileName());
    }

    @Test
    void readsTheMapDate() {
        assertEquals(Optional.of(java.time.LocalDate.of(2023, 3, 10)),
                MapFileName.parse("BR01002303102B83FO00N00E.map").orElseThrow().mapDate());
        assertEquals(Optional.of(java.time.LocalDate.of(2026, 1, 20)),
                MapFileName.parse("BR01002601202B83FO00N00E.map").orElseThrow().mapDate());
    }

    @Test
    void impossibleDateIsEmpty() {
        assertTrue(MapFileName.parse("BR01002313452B83FO00N00E.map").orElseThrow().mapDate().isEmpty());
    }

    @Test
    void rejectsNull() {
        assertTrue(MapFileName.parse(null).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "br01002303102B83FO00N00E.map",
            "BR01002303102B83FO00N00.map",
            "BR01002303102B83FO00N00E.map.bak",
            "BR01002303102B83FO00N00E.MAP",
            "out_1.map",
            ""})
    void rejectsInvalidNames(String name) {
        assertTrue(MapFileName.parse(name).isEmpty());
    }
}
