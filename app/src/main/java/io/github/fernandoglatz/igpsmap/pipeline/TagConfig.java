package io.github.fernandoglatz.igpsmap.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Resolves the tag XML files: a copy in an override folder wins, otherwise the bundled one. */
public final class TagConfig {

    public static final String TAG_CONF = "tag-igpsport.xml";
    public static final String TRANSFORM = "tag-igpsport-transform.xml";

    private TagConfig() {
    }

    public static TagFiles resolve(List<Path> overrideDirs, Path tmpDir) throws IOException {
        return new TagFiles(resolveOne(TAG_CONF, overrideDirs, tmpDir), resolveOne(TRANSFORM, overrideDirs, tmpDir));
    }

    private static Path resolveOne(String name, List<Path> overrideDirs, Path tmpDir) throws IOException {
        for (Path dir : overrideDirs) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Path target = tmpDir.resolve("tags").resolve(name);
        Files.createDirectories(target.getParent());
        try (InputStream in = TagConfig.class.getResourceAsStream("/tags/" + name)) {
            if (in == null) {
                throw new IOException("Bundled tag file missing: " + name);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
