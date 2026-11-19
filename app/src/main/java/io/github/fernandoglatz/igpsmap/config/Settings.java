package io.github.fernandoglatz.igpsmap.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/** User preferences. {@code lastSourceFolder} may be null. */
public record Settings(Path outputDir, int threads, int maxHeapGb, Duration cacheMaxAge, Path lastSourceFolder) {

    private static final int DEFAULT_THREADS = 4;
    private static final int DEFAULT_HEAP_GB = 8;
    private static final int DEFAULT_CACHE_DAYS = 7;

    public static Settings defaults(AppPaths paths) {
        return new Settings(paths.output(), DEFAULT_THREADS, DEFAULT_HEAP_GB, Duration.ofDays(DEFAULT_CACHE_DAYS), null);
    }

    public static Settings load(Path file, AppPaths paths) {
        Settings defaults = defaults(paths);
        if (!Files.isRegularFile(file)) {
            return defaults;
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            p.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            return defaults;
        }
        String output = p.getProperty("outputDir");
        String last = p.getProperty("lastSourceFolder");
        return new Settings(
                pathOr(output, defaults.outputDir()),
                positiveInt(p.getProperty("threads"), DEFAULT_THREADS),
                positiveInt(p.getProperty("maxHeapGb"), DEFAULT_HEAP_GB),
                Duration.ofDays(positiveInt(p.getProperty("cacheMaxAgeDays"), DEFAULT_CACHE_DAYS)),
                pathOr(last, null));
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("outputDir", outputDir.toString());
        p.setProperty("threads", Integer.toString(threads));
        p.setProperty("maxHeapGb", Integer.toString(maxHeapGb));
        p.setProperty("cacheMaxAgeDays", Long.toString(cacheMaxAge.toDays()));
        if (lastSourceFolder != null) {
            p.setProperty("lastSourceFolder", lastSourceFolder.toString());
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.ISO_8859_1)) {
            p.store(writer, "IGPSPORT Map Updater settings");
        }
    }

    public Settings withLastSourceFolder(Path folder) {
        return new Settings(outputDir, threads, maxHeapGb, cacheMaxAge, folder);
    }

    private static Path pathOr(String value, Path fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Path.of(value);
        } catch (java.nio.file.InvalidPathException e) {
            return fallback;
        }
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int v = Integer.parseInt(value == null ? "" : value.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
