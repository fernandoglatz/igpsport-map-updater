package io.github.fernandoglatz.igpsmap.pipeline;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Byte-level fixtures for pipeline tests. */
public final class MapFixtures {

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

    /** Complete header, optional fields and two payloads for metadata relocation tests. */
    public static byte[] completeMap(String creator) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write("mapsforge binary OSM".getBytes(StandardCharsets.US_ASCII));
        out.writeInt(0); out.writeInt(3); out.writeLong(0); out.writeLong(0);
        out.writeInt(-16_070_000); out.writeInt(-48_305_000); out.writeInt(-15_480_000); out.writeInt(-47_290_000);
        out.writeShort(256);
        text(out, "Mercator");
        out.writeByte(creator == null ? 0x78 : 0x7c);
        out.writeLong(0); out.writeByte(13); text(out, "cs"); text(out, "Comment with Mercator inside");
        if (creator != null) text(out, creator);
        out.writeShort(0); out.writeShort(1); text(out, "highway=path");
        out.writeByte(2);
        out.write(new byte[] {13, 13, 13});
        int firstOffset = bytes.size(); out.writeLong(0); out.writeLong(12);
        out.write(new byte[] {14, 14, 14});
        int secondOffset = bytes.size(); out.writeLong(0); out.writeLong(18);
        byte[] header = bytes.toByteArray();
        ByteBuffer fields = ByteBuffer.wrap(header);
        fields.putInt(20, header.length - 24).putLong(28, header.length + 30L);
        fields.putLong(firstOffset, header.length).putLong(secondOffset, header.length + 12L);
        bytes.reset(); bytes.write(header);
        bytes.write("ABCDEFGHIJKLMNOPQRSTUVWXYZ1234".getBytes(StandardCharsets.US_ASCII));
        return bytes.toByteArray();
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int n = bytes.length;
        while (n >= 128) { out.writeByte((n & 127) | 128); n >>>= 7; }
        out.writeByte(n); out.write(bytes);
    }
}
