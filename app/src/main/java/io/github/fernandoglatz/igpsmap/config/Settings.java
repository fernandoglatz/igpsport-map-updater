package io.github.fernandoglatz.igpsmap.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * User preferences. {@code threads} and {@code maxHeapGb} are {@link #AUTO} (0) when the writer tunes
 * them from the PBF size and installed memory. {@code lastSourceFolder} and {@code lastProduct} (a
 * {@code Product} enum name) may be null.
 */
public record Settings(Path outputDir, int threads, int maxHeapGb, Duration cacheMaxAge, Path lastSourceFolder,
                       boolean hdFallback, String lastProduct, String skippedVersion,
                       boolean igs630Compatibility, boolean generateMd5) {

    /** Value of {@code threads} / {@code maxHeapGb} meaning "choose automatically". */
    public static final int AUTO = 0;
    private static final String AUTO_KEYWORD = "auto";
    private static final int DEFAULT_CACHE_DAYS = 7;
    /** Retry a failed in-memory run in disk mode unless the user turned it off. */
    private static final boolean DEFAULT_HD_FALLBACK = true;

    /** Settings without a skipped update version. */
    public Settings(Path outputDir, int threads, int maxHeapGb, Duration cacheMaxAge, Path lastSourceFolder,
                    boolean hdFallback, String lastProduct) {
        this(outputDir, threads, maxHeapGb, cacheMaxAge, lastSourceFolder, hdFallback, lastProduct, null);
    }

    /** Legacy programmatic settings retain the original pipeline behavior. */
    public Settings(Path outputDir, int threads, int maxHeapGb, Duration cacheMaxAge, Path lastSourceFolder,
                    boolean hdFallback, String lastProduct, String skippedVersion) {
        this(outputDir, threads, maxHeapGb, cacheMaxAge, lastSourceFolder, hdFallback, lastProduct, skippedVersion, false, false);
    }

    public static Settings defaults(AppPaths paths) {
        return new Settings(paths.output(), AUTO, AUTO, Duration.ofDays(DEFAULT_CACHE_DAYS), null, DEFAULT_HD_FALLBACK,
                null, null, true, true);
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
        String product = p.getProperty("lastProduct");
        String skipped = p.getProperty("skippedVersion");
        return new Settings(
                pathOr(output, defaults.outputDir()),
                autoOrPositive(p.getProperty("threads")),
                autoOrPositive(p.getProperty("maxHeapGb")),
                Duration.ofDays(positiveInt(p.getProperty("cacheMaxAgeDays"), DEFAULT_CACHE_DAYS)),
                pathOr(last, null),
                booleanOr(p.getProperty("hdFallback"), DEFAULT_HD_FALLBACK),
                product == null || product.isBlank() ? null : product.trim(),
                skipped == null || skipped.isBlank() ? null : skipped.trim(),
                booleanOr(p.getProperty("igs630Compatibility"), true),
                booleanOr(p.getProperty("generateMd5"), true));
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("outputDir", outputDir.toString());
        p.setProperty("threads", autoText(threads));
        p.setProperty("maxHeapGb", autoText(maxHeapGb));
        p.setProperty("cacheMaxAgeDays", Long.toString(cacheMaxAge.toDays()));
        p.setProperty("hdFallback", Boolean.toString(hdFallback));
        p.setProperty("igs630Compatibility", Boolean.toString(igs630Compatibility));
        p.setProperty("generateMd5", Boolean.toString(generateMd5));
        if (lastSourceFolder != null) {
            p.setProperty("lastSourceFolder", lastSourceFolder.toString());
        }
        if (lastProduct != null) {
            p.setProperty("lastProduct", lastProduct);
        }
        if (skippedVersion != null) {
            p.setProperty("skippedVersion", skippedVersion);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.ISO_8859_1)) {
            p.store(writer, "IGPSPORT Map Updater settings");
        }
    }

    public Settings withLastSourceFolder(Path folder) {
        return new Settings(outputDir, threads, maxHeapGb, cacheMaxAge, folder, hdFallback, lastProduct, skippedVersion,
                igs630Compatibility, generateMd5);
    }

    public Settings withLastProduct(String product) {
        return new Settings(outputDir, threads, maxHeapGb, cacheMaxAge, lastSourceFolder, hdFallback, product, skippedVersion,
                igs630Compatibility, generateMd5);
    }

    public Settings withSkippedVersion(String version) {
        return new Settings(outputDir, threads, maxHeapGb, cacheMaxAge, lastSourceFolder, hdFallback, lastProduct, version,
                igs630Compatibility, generateMd5);
    }

    public Settings withGenerationOptions(boolean compatibility, boolean md5) {
        return new Settings(outputDir, threads, maxHeapGb, cacheMaxAge, lastSourceFolder, hdFallback, lastProduct,
                skippedVersion, compatibility, md5);
    }

    private static boolean booleanOr(String value, boolean fallback) {
        if (value == null || !(value.trim().equalsIgnoreCase("true") || value.trim().equalsIgnoreCase("false"))) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static String autoText(int value) {
        return value > 0 ? Integer.toString(value) : AUTO_KEYWORD;
    }

    /** A positive number, or AUTO for "auto", missing or invalid values. */
    private static int autoOrPositive(String value) {
        if (value == null || value.trim().equalsIgnoreCase(AUTO_KEYWORD)) {
            return AUTO;
        }
        return positiveInt(value, AUTO);
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
