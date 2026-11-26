package io.github.fernandoglatz.igpsmap.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import org.junit.jupiter.api.Test;

class TileKeyTest {

    @Test
    void parentCoversTheChild() {
        TileKey child = new TileKey(5, 7, 4);

        assertEquals(new TileKey(2, 3, 3), child.parent());
    }

    @Test
    void theWorldTileHasNoParent() {
        assertThrows(IllegalStateException.class, () -> new TileKey(0, 0, 0).parent());
    }

    @Test
    void rejectsTilesOutsideTheWorld() {
        assertThrows(IllegalArgumentException.class, () -> new TileKey(2, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TileKey(0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TileKey(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new TileKey(0, 0, 31));
    }

    @Test
    void intersectsOnlyBoxesThatOverlapIt() {
        // Zoom 1: tile (0,0) is the north-west quarter of the world.
        TileKey northWest = new TileKey(0, 0, 1);

        assertTrue(northWest.intersects(new BoundingBox(10, -100, 20, -90)));
        assertFalse(northWest.intersects(new BoundingBox(-20, -100, -10, -90))); // south
        assertFalse(northWest.intersects(new BoundingBox(10, 90, 20, 100))); // east
    }
}
