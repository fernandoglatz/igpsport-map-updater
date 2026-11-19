package io.github.fernandoglatz.igpsmap.catalog;

import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.ProgressListener;
import io.github.fernandoglatz.igpsmap.net.RemoteZip;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Turns an official catalog entry into local original map files under
 * {@code <originals>/<catalog id>/}. Only main maps (file names {@link MapFileName} accepts) are kept;
 * from ZIP packages just those entries are downloaded. The listener may throw
 * {@link java.util.concurrent.CancellationException} to cancel, which propagates.
 */
public final class OfficialMapDownloader {

    /** Maps obtained for {@code node}, or the reason there are none ({@code error} is null on success). */
    public record Result(CatalogNode node, List<Path> maps, String error) {
        public Result {
            maps = List.copyOf(maps);
        }
    }

    private final HttpFetcher fetcher;
    private final Path originals;

    public OfficialMapDownloader(HttpFetcher fetcher, Path originals) {
        this.fetcher = fetcher;
        this.originals = originals;
    }

    public Result fetch(CatalogNode node, Product product, ProgressListener progress) {
        if (node.url() == null) {
            return new Result(node, List.of(), node.name() + " has no download link");
        }
        Path dir = originals.resolve(product.catalogId());
        try {
            List<Path> cached = cached(dir, node.url());
            if (cached != null) {
                return new Result(node, cached, null);
            }
            List<Path> maps = isBareMap(node.url()) ? List.of(downloadMap(node, dir, progress))
                    : fromPackage(node, dir, progress);
            if (maps.isEmpty()) {
                return new Result(node, List.of(), "No usable map in " + node.name());
            }
            remember(dir, node.url(), maps);
            return new Result(node, maps, null);
        } catch (RemoteZip.NotAZipException e) {
            return new Result(node, List.of(), node.name() + " is not a map package");
        } catch (IOException e) {
            return new Result(node, List.of(), node.name() + ": " + e.getMessage());
        }
    }

    private Path downloadMap(CatalogNode node, Path dir, ProgressListener progress) throws IOException {
        String name = RemoteZip.baseName(node.url().getPath());
        if (MapFileName.parse(name).isEmpty()) {
            throw new IOException("unexpected map file name " + name);
        }
        Path target = dir.resolve(name);
        fetcher.download(node.url(), target, progress);
        return target;
    }

    private List<Path> fromPackage(CatalogNode node, Path dir, ProgressListener progress) throws IOException {
        RemoteZip zip;
        try {
            zip = RemoteZip.open(fetcher, node.url());
        } catch (RemoteZip.RangeNotSupportedException e) {
            return fromFullDownload(node, dir, progress);
        }
        List<RemoteZip.Entry> wanted = zip.entries().stream()
                .filter(entry -> !entry.directory() && isMainMap(RemoteZip.baseName(entry.name())))
                .toList();
        long total = wanted.stream().mapToLong(RemoteZip.Entry::size).sum();
        List<Path> maps = new ArrayList<>();
        long before = 0;
        for (RemoteZip.Entry entry : wanted) {
            Path target = dir.resolve(RemoteZip.baseName(entry.name()));
            long offset = before;
            zip.extract(entry, target, (done, size) -> progress.onProgress(offset + done, total));
            maps.add(target);
            before += entry.size();
        }
        return maps;
    }

    private List<Path> fromFullDownload(CatalogNode node, Path dir, ProgressListener progress) throws IOException {
        Path zipFile = dir.resolve(".package-" + hash(node.url()) + ".zip");
        try {
            fetcher.download(node.url(), zipFile, progress);
            return RemoteZip.extractLocally(zipFile, OfficialMapDownloader::isMainMap, dir);
        } catch (java.util.zip.ZipException e) {
            throw new RemoteZip.NotAZipException(e.getMessage());
        } finally {
            Files.deleteIfExists(zipFile);
        }
    }

    /** Files of an earlier successful fetch of {@code url}, or null when they must be downloaded. */
    private static List<Path> cached(Path dir, URI url) throws IOException {
        Path marker = marker(dir, url);
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        List<Path> maps = new ArrayList<>();
        for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
            String name = line.strip();
            if (!isMainMap(name) || !Files.isRegularFile(dir.resolve(name))) {
                return null;
            }
            maps.add(dir.resolve(name));
        }
        return maps.isEmpty() ? null : maps;
    }

    private static void remember(Path dir, URI url, List<Path> maps) throws IOException {
        Path marker = marker(dir, url);
        Files.createDirectories(marker.getParent());
        Files.write(marker, maps.stream().map(p -> p.getFileName().toString()).toList(), StandardCharsets.UTF_8);
    }

    private static Path marker(Path dir, URI url) {
        return dir.resolve(".fetched").resolve(hash(url));
    }

    private static boolean isBareMap(URI url) {
        return url.getPath() != null && url.getPath().toLowerCase(Locale.ROOT).endsWith(".map");
    }

    private static boolean isMainMap(String fileName) {
        return MapFileName.parse(fileName).isPresent();
    }

    private static String hash(URI url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
