package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.config.AppPaths;
import io.github.fernandoglatz.igpsmap.config.Settings;
import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.device.DetectedSource;
import io.github.fernandoglatz.igpsmap.device.DeviceScanner;
import io.github.fernandoglatz.igpsmap.io.MapsCsv;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import io.github.fernandoglatz.igpsmap.matching.Matcher;
import io.github.fernandoglatz.igpsmap.matching.PolySource;
import io.github.fernandoglatz.igpsmap.net.DiskCache;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.pipeline.CancelToken;
import io.github.fernandoglatz.igpsmap.pipeline.GenerationListener;
import io.github.fernandoglatz.igpsmap.pipeline.GenerationService;
import io.github.fernandoglatz.igpsmap.pipeline.JobResult;
import io.github.fernandoglatz.igpsmap.pipeline.OsmosisCommand;
import io.github.fernandoglatz.igpsmap.pipeline.ProcessRunner;
import io.github.fernandoglatz.igpsmap.pipeline.SystemProcessRunner;
import io.github.fernandoglatz.igpsmap.sources.GeofabrikIndex;
import io.github.fernandoglatz.igpsmap.sources.GeofabrikRegion;
import io.github.fernandoglatz.igpsmap.sources.OsmBoundarySource;
import io.github.fernandoglatz.igpsmap.sources.OsmFrPolygonCrawler;
import io.github.fernandoglatz.igpsmap.sources.PolyCandidate;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** UI-independent application logic; every method may block and runs off the FX thread. */
public final class AppController {

    private static final int CRAWL_DEPTH = 3;
    private static final int CRAWL_CONCURRENCY = 4;
    private static final java.time.Duration OVERPASS_TIMEOUT = java.time.Duration.ofMinutes(4);

    private final AppPaths paths;
    private final DeviceScanner scanner;
    private final HttpFetcher fetcher;
    private final ProcessRunner runner;
    private volatile Settings settings;

    private volatile GeofabrikIndex index;
    private final Map<String, CountryPolygons> polygonsByCountry = new ConcurrentHashMap<>();
    private final Map<String, CountryPolygons> boundariesByCountry = new ConcurrentHashMap<>();

    /** Crawl result per country: candidates, or the error that prevented the crawl. */
    private record CountryPolygons(List<PolyCandidate> candidates, IOException error) {
    }

    public AppController(AppPaths paths, Settings settings, DeviceScanner scanner, HttpFetcher fetcher, ProcessRunner runner) {
        this.paths = paths;
        this.settings = settings;
        this.scanner = scanner;
        this.fetcher = fetcher;
        this.runner = runner;
    }

    public static AppController createDefault(Path cwd) {
        AppPaths paths = AppPaths.detect(cwd);
        return new AppController(paths, Settings.load(paths.settingsFile(), paths), DeviceScanner.system(),
                HttpFetcher.createDefault(), new SystemProcessRunner());
    }

    public AppPaths paths() {
        return paths;
    }

    public Settings settings() {
        return settings;
    }

    public void updateSettings(Settings newSettings) {
        settings = newSettings;
        try {
            newSettings.save(paths.settingsFile());
        } catch (IOException e) {
            // Settings are a convenience; failing to persist them must not block the user.
        }
    }

    public List<DetectedSource> detect() {
        return scanner.detectDevices();
    }

    public List<DetectedMap> scanFolder(Path folder) {
        return scanner.scan(folder, DeviceScanner.DEFAULT_DEPTH);
    }

    /** Matches every map; progress receives 0..1. Fails only if the Geofabrik index cannot load. */
    public List<MapJob> match(List<DetectedMap> maps, Consumer<Double> progress) throws IOException {
        DiskCache cache = cache();
        if (index == null) {
            index = GeofabrikIndex.load(fetcher, cache);
        }
        OsmFrPolygonCrawler crawler = new OsmFrPolygonCrawler(fetcher, cache, OsmFrPolygonCrawler.DEFAULT_BASE,
                CRAWL_DEPTH, CRAWL_CONCURRENCY);
        // Overpass answers for large countries can take a few minutes.
        OsmBoundarySource boundaries = new OsmBoundarySource(fetcher.withTimeout(OVERPASS_TIMEOUT), cache,
                OsmBoundarySource.DEFAULT_OVERPASS_ENDPOINTS, OsmBoundarySource.DEFAULT_POLYGONS,
                OsmBoundarySource.DEFAULT_BACKOFFS);
        Matcher.BoundarySource boundarySource = new Matcher.BoundarySource() {
            @Override
            public List<PolyCandidate> forCountry(String countryCode) throws IOException {
                return boundariesFor(boundaries, countryCode);
            }

            @Override
            public org.locationtech.jts.geom.Geometry geometry(PolyCandidate candidate) throws IOException {
                return boundaries.geometry(candidate);
            }
        };
        Matcher matcher = new Matcher(index, country -> polygonsFor(crawler, country), boundarySource,
                MapsCsv.read(paths.mapsCsv()));
        List<MapJob> jobs = new ArrayList<>();
        for (int i = 0; i < maps.size(); i++) {
            jobs.add(matcher.match(maps.get(i)));
            progress.accept((i + 1.0) / maps.size());
        }
        return jobs;
    }

    /** Forgets cached sources so the next match fetches fresh listings and polygons. */
    public void refreshSources() throws IOException {
        cache().clear();
        index = null;
        polygonsByCountry.clear();
        boundariesByCountry.clear();
    }

    /** Saves confirmed rows that have a downloadable polygon to maps.csv. */
    public void confirm(List<MapJob> jobs) throws IOException {
        List<MapsCsv.Row> rows = new ArrayList<>();
        for (MapJob job : jobs) {
            if (job.pbfUrl() != null && job.poly() instanceof PolySource.Remote remote) {
                rows.add(new MapsCsv.Row(job.map().path().getFileName().toString(),
                        job.pbfUrl().toString(), remote.url().toString()));
            }
        }
        if (!rows.isEmpty()) {
            MapsCsv.upsert(paths.mapsCsv(), rows);
        }
    }

    public List<JobResult> generate(List<MapJob> jobs, GenerationListener listener, CancelToken cancel) {
        GenerationService service = new GenerationService(fetcher, runner, paths, settings,
                OsmosisCommand.currentJavaExecutable(), System.getProperty("java.class.path"), tagOverrideDirs());
        return service.runAll(jobs, listener, cancel);
    }

    /** Stops any running osmosis process; called when the window closes. */
    public void shutdown() {
        if (runner instanceof SystemProcessRunner system) {
            system.killAll();
        }
    }

    public static List<MapJob> preselected(List<MapJob> jobs) {
        return jobs.stream().filter(j -> j.confidence().preselected()).toList();
    }

    private List<Path> tagOverrideDirs() {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        dirs.add(paths.root());
        dirs.add(Path.of("").toAbsolutePath());
        return List.copyOf(dirs);
    }

    private DiskCache cache() {
        return new DiskCache(paths.cache(), settings.cacheMaxAge(), Clock.systemUTC());
    }

    /** One Overpass query per country and session; a failure is remembered until "Refresh sources". */
    private List<PolyCandidate> boundariesFor(OsmBoundarySource source, String countryCode) throws IOException {
        CountryPolygons result = boundariesByCountry.computeIfAbsent(countryCode, cc -> {
            try {
                return new CountryPolygons(source.boundaries(cc), null);
            } catch (IOException e) {
                return new CountryPolygons(List.of(), e);
            }
        });
        if (result.error() != null) {
            throw result.error();
        }
        return result.candidates();
    }

    private List<PolyCandidate> polygonsFor(OsmFrPolygonCrawler crawler, GeofabrikRegion country) throws IOException {
        CountryPolygons result = polygonsByCountry.computeIfAbsent(country.id(), id -> {
            try {
                String path = crawler.findCountryPath(GeofabrikIndex.pathOf(country), country.id(), country.name())
                        .orElseThrow(() -> new IOException("No openstreetmap.fr polygons found for " + country.name()));
                return new CountryPolygons(crawler.crawl(path), null);
            } catch (IOException e) {
                return new CountryPolygons(List.of(), e);
            }
        });
        if (result.error() != null) {
            throw result.error();
        }
        return result.candidates();
    }
}
