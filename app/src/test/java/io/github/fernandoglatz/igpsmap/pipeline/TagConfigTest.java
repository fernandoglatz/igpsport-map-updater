package io.github.fernandoglatz.igpsmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TagConfigTest {

    @TempDir
    Path dir;

    @Test
    void extractsBundledFilesWhenNoOverride() throws IOException {
        TagFiles tags = TagConfig.resolve(List.of(dir.resolve("nothing-here")), dir.resolve("tmp"));
        assertTrue(Files.readString(tags.tagConf()).contains("<tag-mapping"));
        assertTrue(Files.readString(tags.transform()).contains("highway"));
        assertEquals(dir.resolve("tmp/tags/tag-igpsport.xml"), tags.tagConf());
    }

    @Test
    void compatibilityProfileKeepsPathsAndExcludesWaterwaysDespiteGenericOverrides() throws IOException {
        Path custom = dir.resolve("custom");
        Files.createDirectories(custom);
        Files.writeString(custom.resolve("tag-igpsport.xml"), "<enhanced-override/>");
        TagFiles tags = TagConfig.resolve(List.of(custom), dir.resolve("tmp"), true);
        String mapping = Files.readString(tags.tagConf());
        String transforms = Files.readString(tags.transform());
        assertTrue(mapping.contains("value=\"footway\"") && mapping.contains("value=\"path\""));
        assertTrue(!mapping.contains("key=\"waterway\"") && !mapping.contains("enhanced-override"));
        assertTrue(!transforms.contains("v=\"footway\"") && !transforms.contains("v=\"path\""));
    }

    @Test
    void overrideDirectoryWinsPerFile() throws IOException {
        Path custom = dir.resolve("custom");
        Files.createDirectories(custom);
        Files.writeString(custom.resolve("tag-igpsport.xml"), "<custom/>");
        TagFiles tags = TagConfig.resolve(List.of(custom), dir.resolve("tmp"));
        assertEquals(custom.resolve("tag-igpsport.xml"), tags.tagConf());
        assertEquals(dir.resolve("tmp/tags/tag-igpsport-transform.xml"), tags.transform());
    }
}
