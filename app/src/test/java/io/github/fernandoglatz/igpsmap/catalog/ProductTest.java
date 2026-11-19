package io.github.fernandoglatz.igpsmap.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductTest {

    @Test
    void detectsTheLongestMatchingProductName() {
        assertEquals(Optional.of(Product.IGS630S), Product.detect("IGS630S"));
        assertEquals(Optional.of(Product.IGS630), Product.detect("igs630"));
        assertEquals(Optional.of(Product.BSC300T), Product.detect("BSC300T"));
        assertEquals(Optional.of(Product.BSC300), Product.detect("BSC 300"));
        assertEquals(Optional.of(Product.BINAVI_AIR), Product.detect("BiNavi Air"));
        assertEquals(Optional.of(Product.BINAVI), Product.detect("BINAVI"));
        assertEquals(Optional.of(Product.IGS800), Product.detect("iGPSPORT-iGS800"));
    }

    @Test
    void unknownNamesAreNotDetected() {
        assertTrue(Product.detect("NO NAME").isEmpty());
        assertTrue(Product.detect("").isEmpty());
        assertTrue(Product.detect(null).isEmpty());
    }

    @Test
    void productsShareCatalogsByMapFormat() {
        assertEquals("1f504832-9c67-11ef-a0af-000c29d603cc", Product.IGS630.catalogId());
        assertEquals(Product.IGS630.catalogId(), Product.BSC300.catalogId());
        assertEquals(Product.IGS630.catalogId(), Product.BSC300T.catalogId());
        assertEquals("20a0bbc3-9c67-11ef-a0af-000c29d603cc", Product.IGS630S.catalogId());
        assertEquals("a959751a-21b5-44a3-b713-bc12ebb208ef", Product.IGS800.catalogId());
        assertEquals(Product.IGS800.catalogId(), Product.BINAVI.catalogId());
    }

    @Test
    void parsesEnumNamesFromSettings() {
        assertEquals(Optional.of(Product.BINAVI_AIR), Product.fromName("BINAVI_AIR"));
        assertTrue(Product.fromName("BINAVI_ULTRA").isEmpty());
        assertTrue(Product.fromName(null).isEmpty());
    }

    @Test
    void showsTheMarketingName() {
        assertEquals("BiNavi Air", Product.BINAVI_AIR.toString());
    }
}
