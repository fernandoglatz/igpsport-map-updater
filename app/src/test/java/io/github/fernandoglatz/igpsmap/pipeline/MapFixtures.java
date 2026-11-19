package io.github.fernandoglatz.igpsmap.pipeline;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Byte-level fixtures for pipeline tests. */
final class MapFixtures {

    private MapFixtures() {
    }

    /** A Mapsforge header with the Distrito Federal bounding box of the real 2026 map. */
    static byte[] distritoFederalMap() {
        ByteBuffer buf = ByteBuffer.allocate(80);
        buf.put("mapsforge binary OSM".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(499).putInt(3).putLong(80).putLong(0);
        buf.putInt(-16_070_000).putInt(-48_305_000).putInt(-15_480_000).putInt(-47_290_000);
        return buf.array();
    }
}
