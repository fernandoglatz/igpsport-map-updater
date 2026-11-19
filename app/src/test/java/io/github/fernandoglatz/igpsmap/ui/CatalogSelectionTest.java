package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.catalog.CatalogNode;
import io.github.fernandoglatz.igpsmap.catalog.Product;
import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogSelectionTest {

    private static CatalogNode node(String url, String... path) {
        return new CatalogNode("id", path[path.length - 1], List.of(path), 1, "", url == null ? null : URI.create(url), List.of());
    }

    private static DetectedMap map(String fileName) {
        return new DetectedMap(Path.of("/dev", fileName), MapFileName.parse(fileName).orElseThrow());
    }

    @Test
    void entriesWhoseCodeIsAlreadyInTheListAreMarked() {
        List<DetectedMap> maps = List.of(map("BR01002303102B83FO00N00E.map"), map("FR940024071744O3J900E00D.map"));
        assertTrue(CatalogSelection.inList(node("https://h/V1/BR0100.zip", "Brazil", "DF"), maps));
        assertTrue(CatalogSelection.inList(node("https://h/iGS800/FR94.zip", "Reunion"), maps));
        assertFalse(CatalogSelection.inList(node("https://h/V1/BR2500.zip", "Brazil", "SP"), maps));
    }

    @Test
    void entriesWithoutACodeAreNeverMarked() {
        List<DetectedMap> maps = List.of(map("AZ00002303102B83FO00N00E.map"));
        assertFalse(CatalogSelection.inList(node("https://h/3ba56f27-bd9f", "Azerbaijan"), maps));
        assertFalse(CatalogSelection.inList(node(null, "Asia"), maps));
    }

    @Test
    void filterIgnoresCaseAndAccentsAndSearchesThePath() {
        CatalogNode sp = node("https://h/BR2500.zip", "south-america", "Brazil", "São Paulo");
        assertTrue(CatalogSelection.matches(sp, "sao"));
        assertTrue(CatalogSelection.matches(sp, "BRAZIL"));
        assertTrue(CatalogSelection.matches(sp, "  "));
        assertFalse(CatalogSelection.matches(sp, "portugal"));
    }

    @Test
    void preselectsTheProductFromTheDeviceLabels() {
        assertEquals(Optional.of(Product.IGS630S), CatalogSelection.preselect(List.of("IGS630S"), "BINAVI"));
        assertEquals(Optional.of(Product.IGS630S), CatalogSelection.preselect(List.of("", "IGS630S"), null));
    }

    @Test
    void fallsBackToTheLastProductUsed() {
        assertEquals(Optional.of(Product.BINAVI), CatalogSelection.preselect(List.of("maps-backup"), "BINAVI"));
        assertEquals(Optional.empty(), CatalogSelection.preselect(List.of(), null));
        assertEquals(Optional.empty(), CatalogSelection.preselect(List.of(), "UNKNOWN"));
    }

    @Test
    void formatsPackageSizes() {
        assertEquals("31 KB", CatalogSelection.size(31_310));
        assertEquals("34.0 MB", CatalogSelection.size(34_000_000));
        assertEquals("1.20 GB", CatalogSelection.size(1_200_000_000));
        assertEquals("", CatalogSelection.size(0));
    }
}
