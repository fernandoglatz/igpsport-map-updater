package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlInputTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://download.openstreetmap.fr/polygons/europe/portugal.poly",
            "  http://download.geofabrik.de/europe/portugal-latest.osm.pbf  "})
    void acceptsHttpUrlsWithAPath(String text) {
        assertTrue(UrlInput.isHttpUrl(text));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ftp://x/y.poly", "https://host", "https://host/", "not a url", "file:///etc/passwd", "https:// bad", "https://host/a,b.poly"})
    void rejectsEverythingElse(String text) {
        assertFalse(UrlInput.isHttpUrl(text));
    }
}
