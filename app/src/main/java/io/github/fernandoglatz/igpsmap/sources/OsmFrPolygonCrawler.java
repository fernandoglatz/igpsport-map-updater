package io.github.fernandoglatz.igpsmap.sources;

import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.io.PolyFile;
import io.github.fernandoglatz.igpsmap.net.DiskCache;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.locationtech.jts.geom.Geometry;

/** Crawls the plain directory listings of {@code https://download.openstreetmap.fr/polygons/}. */
public final class OsmFrPolygonCrawler {

    public static final URI DEFAULT_BASE = URI.create("https://download.openstreetmap.fr/polygons/");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");

    private final HttpFetcher fetcher;
    private final DiskCache cache;
    private final URI base;
    private final int maxDepth;
    private final int concurrency;

    public OsmFrPolygonCrawler(HttpFetcher fetcher, DiskCache cache, URI base, int maxDepth, int concurrency) {
        this.fetcher = fetcher;
        this.cache = cache;
        this.base = base;
        this.maxDepth = maxDepth;
        this.concurrency = concurrency;
    }

    /**
     * All polygons for a country: the country's own {@code <path>.poly} plus every {@code .poly}
     * up to {@code maxDepth} folders below {@code <path>/}. Unreadable folders and malformed
     * polygons are skipped; only a missing country folder is an error.
     */
    public List<PolyCandidate> crawl(String countryPath) throws IOException {
        String root = trimSlashes(countryPath);
        List<URI> polyUrls = new ArrayList<>();
        polyUrls.add(base.resolve(root + ".poly"));

        List<String> level = List.of(root + "/");
        try {
            listOrThrow(level.get(0));
        } catch (IOException e) {
            if (!polyListed(root)) {
                throw e; // neither a folder nor a polygon for this country
            }
            level = List.of(); // small countries are a single <continent>/<country>.poly
        }
        for (int depth = 0; depth <= maxDepth && !level.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String folder : level) {
                List<String> entries;
                try {
                    entries = listOrThrow(folder);
                } catch (IOException e) {
                    continue;
                }
                for (String entry : entries) {
                    if (entry.endsWith("/")) {
                        next.add(folder + entry);
                    } else if (entry.endsWith(".poly")) {
                        polyUrls.add(base.resolve(folder + entry));
                    }
                }
            }
            level = next;
        }
        return fetchPolygons(new LinkedHashSet<>(polyUrls));
    }

    /**
     * Finds the folder for a country on openstreetmap.fr: the Geofabrik path first, then the
     * id (also with '_') or lowercase name below every top-level folder.
     */
    public Optional<String> findCountryPath(String geofabrikPath, String id, String name) throws IOException {
        String path = trimSlashes(geofabrikPath);
        if (folderExists(path) || polyListed(path)) {
            return Optional.of(path);
        }
        Set<String> variants = new LinkedHashSet<>();
        for (String v : List.of(id, name.toLowerCase(Locale.ROOT))) {
            variants.add(v);
            variants.add(v.replace('-', '_').replace(' ', '_'));
            variants.add(v.replace('_', '-').replace(' ', '-'));
        }
        for (String continent : listOrThrow("")) {
            if (!continent.endsWith("/")) {
                continue;
            }
            List<String> entries;
            try {
                entries = listOrThrow(continent);
            } catch (IOException e) {
                continue;
            }
            for (String variant : variants) {
                if (entries.contains(variant + "/") || entries.contains(variant + ".poly")) {
                    return Optional.of(continent + variant);
                }
            }
        }
        return Optional.empty();
    }

    public static List<String> parseListing(String html) {
        List<String> entries = new ArrayList<>();
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            if (href.startsWith("?") || href.startsWith("/") || href.startsWith("..") || href.contains("://")) {
                continue;
            }
            entries.add(href);
        }
        return entries;
    }

    private boolean folderExists(String path) {
        try {
            listOrThrow(path + "/");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** True when {@code <path>.poly} appears in its parent folder's listing. */
    private boolean polyListed(String path) {
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "" : path.substring(0, slash + 1);
        String name = path.substring(slash + 1);
        try {
            return listOrThrow(parent).contains(name + ".poly");
        } catch (IOException e) {
            return false;
        }
    }

    private List<String> listOrThrow(String folder) throws IOException {
        URI uri = base.resolve(folder);
        return parseListing(cache.getOrFetch(uri.toString(), () -> fetcher.getText(uri)));
    }

    private List<PolyCandidate> fetchPolygons(Set<URI> urls) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
        try {
            List<Future<Optional<PolyCandidate>>> futures = new ArrayList<>();
            for (URI url : urls) {
                futures.add(pool.submit(() -> fetchPolygon(url)));
            }
            List<PolyCandidate> out = new ArrayList<>();
            for (Future<Optional<PolyCandidate>> future : futures) {
                future.get().ifPresent(out::add);
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching polygons", e);
        } catch (ExecutionException e) {
            throw new IOException("Polygon fetch failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private Optional<PolyCandidate> fetchPolygon(URI url) {
        try {
            String text = cache.getOrFetch(url.toString(), () -> fetcher.getText(url));
            Geometry geometry = PolyFile.parse(text);
            String file = url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
            String name = file.substring(0, file.length() - ".poly".length());
            return Optional.of(new PolyCandidate(url, name, geometry,
                    GeoCode.toTileBox(PolyFile.boundingBox(geometry))));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String trimSlashes(String path) {
        String p = path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
