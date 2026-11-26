package io.github.fernandoglatz.igpsmap.io;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fernandoglatz.igpsmap.pipeline.MapFixtures;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Igs630MapCompatibilityTest {
    @TempDir Path dir;

    @Test
    void resizesCreatorAndRelocatesAllSubfilesWithoutChangingPayload() throws IOException {
        Path original = Files.write(dir.resolve("original.map"), MapFixtures.completeMap("vendor\u0000Q"));
        byte[] originalBytes = Files.readAllBytes(original);
        Path generated = Files.write(dir.resolve("generated.map"), MapFixtures.completeMap("writer-master-SNAPSHOT"));
        Igs630MapCompatibility.patch(original, generated);
        assertArrayEquals("vendor\u0000Q".getBytes(StandardCharsets.UTF_8), Igs630MapCompatibility.readCreatedBy(generated));
        assertArrayEquals(originalBytes, Files.readAllBytes(original));
        assertLayout(generated);
    }

    @Test
    void handlesCreatorLengthCrossingTheVariableLengthBoundary() throws IOException {
        String creator = "v".repeat(130) + "Q";
        Path original = Files.write(dir.resolve("original.map"), MapFixtures.completeMap(creator));
        Path generated = Files.write(dir.resolve("generated.map"), MapFixtures.completeMap("short"));
        Igs630MapCompatibility.patch(original, generated);
        assertArrayEquals(creator.getBytes(StandardCharsets.UTF_8), Igs630MapCompatibility.readCreatedBy(generated));
        assertLayout(generated);
    }

    @Test
    void v3TemplateUsesKnownOfficialV1MetadataInsteadOfV3Writer() throws IOException {
        Path original = Files.write(dir.resolve("v3.map"), MapFixtures.completeMap("mapsforge-map-writer-0.16.0"));
        Path generated = Files.write(dir.resolve("generated.map"), MapFixtures.completeMap("writer-master-SNAPSHOT"));
        Igs630MapCompatibility.patch(original, generated);
        assertEquals("mapsforge-map-writer-0.16.Q",
                new String(Igs630MapCompatibility.readCreatedBy(generated), StandardCharsets.UTF_8));
        assertLayout(generated);
    }

    @Test
    void addsMissingCreatorFieldAndPreservesOtherOptionalFields() throws IOException {
        Path original = Files.write(dir.resolve("original.map"), MapFixtures.completeMap("originalQ"));
        Path generated = Files.write(dir.resolve("generated.map"), MapFixtures.completeMap(null));
        Igs630MapCompatibility.patch(original, generated);
        assertEquals("originalQ", new String(Igs630MapCompatibility.readCreatedBy(generated), StandardCharsets.UTF_8));
        assertLayout(generated);
    }

    @Test
    void invalidOriginalDoesNotModifyGeneratedMap() throws IOException {
        Path original = Files.writeString(dir.resolve("bad.map"), "not a map");
        byte[] data = MapFixtures.completeMap("generated");
        Path generated = Files.write(dir.resolve("generated.map"), data);
        assertThrows(IOException.class, () -> Igs630MapCompatibility.patch(original, generated));
        assertArrayEquals(data, Files.readAllBytes(generated));
    }

    private static void assertLayout(Path map) throws IOException {
        byte[] bytes = Files.readAllBytes(map);
        ByteBuffer fields = ByteBuffer.wrap(bytes);
        int headerEnd = fields.getInt(20) + 24;
        assertEquals(bytes.length, fields.getLong(28));
        assertEquals(headerEnd, fields.getLong(headerEnd - 38 + 3));
        assertEquals(headerEnd + 12, fields.getLong(headerEnd - 19 + 3));
        assertArrayEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ1234".getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOfRange(bytes, headerEnd, bytes.length));
    }
}
