package io.github.fernandoglatz.igpsmap.pipeline;

import io.github.fernandoglatz.igpsmap.config.AppPaths;
import io.github.fernandoglatz.igpsmap.config.Settings;
import io.github.fernandoglatz.igpsmap.io.PbfDateReader;
import io.github.fernandoglatz.igpsmap.io.PolyFile;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import io.github.fernandoglatz.igpsmap.matching.PolySource;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.ProgressListener;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/** Runs confirmed jobs one at a time: download, osmosis, rename (spec section 4). */
public final class GenerationService {

    private final HttpFetcher fetcher;
    private final ProcessRunner runner;
    private final AppPaths paths;
    private final Settings settings;
    private final Path javaExe;
    private final String classpath;
    private final List<Path> tagOverrideDirs;

    public GenerationService(HttpFetcher fetcher, ProcessRunner runner, AppPaths paths, Settings settings,
                             Path javaExe, String classpath, List<Path> tagOverrideDirs) {
        this.fetcher = fetcher;
        this.runner = runner;
        this.paths = paths;
        this.settings = settings;
        this.javaExe = javaExe;
        this.classpath = classpath;
        this.tagOverrideDirs = List.copyOf(tagOverrideDirs);
    }

    public List<JobResult> runAll(List<MapJob> jobs, GenerationListener listener, CancelToken cancel) {
        cancel.bind(Thread.currentThread());
        try {
            return runJobs(jobs, listener, cancel);
        } finally {
            cancel.unbind();
            Thread.interrupted(); // a cancel may have interrupted this thread; don't leak the flag
        }
    }

    private List<JobResult> runJobs(List<MapJob> jobs, GenerationListener listener, CancelToken cancel) {
        List<JobResult> results = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            MapJob job = jobs.get(i);
            JobResult result;
            if (cancel.isCancelled()) {
                result = new JobResult(job, JobResult.Status.SKIPPED, null, "Cancelled before start");
            } else if (!job.runnable()) {
                result = new JobResult(job, JobResult.Status.SKIPPED, null,
                        job.error() != null ? job.error() : "No PBF or polygon selected");
            } else {
                listener.onJobStart(i + 1, jobs.size(), job);
                result = runOne(job, listener, cancel);
            }
            results.add(result);
            listener.onJobDone(result);
        }
        return results;
    }

    private JobResult runOne(MapJob job, GenerationListener listener, CancelToken cancel) {
        Path tmpMap = paths.tmp().resolve("out_" + job.map().name().regionKey() + ".map");
        try {
            Files.createDirectories(paths.tmp());
            Files.deleteIfExists(tmpMap);

            listener.onStep("Downloading PBF");
            Path pbf = ensurePbf(job.pbfUrl(), listener, cancel);

            listener.onStep("Preparing polygon");
            Path poly = ensurePoly(job, cancel);

            TagFiles tags = TagConfig.resolve(tagOverrideDirs, paths.tmp());
            listener.onStep("Running osmosis");
            listener.onProgress(-1);
            List<String> command = OsmosisCommand.build(javaExe, classpath, settings.maxHeapGb(), settings.threads(),
                    paths.tmp(), pbf, poly, tags, tmpMap);
            int exit = runner.run(command, listener::onLog, cancel);
            if (cancel.isCancelled()) {
                throw new CancellationException();
            }
            if (exit != 0) {
                return new JobResult(job, JobResult.Status.FAILED, null, "osmosis failed with exit code " + exit);
            }
            if (!Files.exists(tmpMap)) {
                return new JobResult(job, JobResult.Status.FAILED, null, "osmosis produced no file");
            }

            listener.onStep("Renaming");
            Path output = MapRenamer.rename(tmpMap, job.map().name(), PbfDateReader.resolveDate(pbf), settings.outputDir());
            return new JobResult(job, JobResult.Status.SUCCESS, output, "Generated " + output.getFileName());
        } catch (CancellationException e) {
            return new JobResult(job, JobResult.Status.CANCELLED, null, "Cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new JobResult(job, JobResult.Status.CANCELLED, null, "Interrupted");
        } catch (IOException | RuntimeException e) {
            if (cancel.isCancelled()) {
                return new JobResult(job, JobResult.Status.CANCELLED, null, "Cancelled");
            }
            return new JobResult(job, JobResult.Status.FAILED, null, String.valueOf(e.getMessage()));
        } finally {
            try {
                Files.deleteIfExists(tmpMap);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }

    private Path ensurePbf(URI url, GenerationListener listener, CancelToken cancel) throws IOException {
        // PBFs are stored flat: download/<file name>.
        Path target = paths.download().resolve(downloadPath(paths.download(), url).getFileName());
        listener.onLog("Checking for newer data: " + target.getFileName());
        Optional<Instant> remote = fetcher.lastModified(url);
        if (Files.exists(target)) {
            Instant local = Files.getLastModifiedTime(target).toInstant();
            if (remote.isEmpty() || !local.isBefore(remote.get())) {
                listener.onLog("Using cached " + target.getFileName());
                return target;
            }
            listener.onLog("Newer data available for " + target.getFileName());
        }
        listener.onLog("Downloading " + url);
        fetcher.download(url, target, progress(listener, cancel));
        if (remote.isPresent()) {
            Files.setLastModifiedTime(target, FileTime.from(remote.get()));
        }
        return target;
    }

    private Path ensurePoly(MapJob job, CancelToken cancel) throws IOException {
        if (job.poly() instanceof PolySource.FromBoundingBox bbox) {
            String name = "bbox-" + job.map().name().regionKey();
            Path target = paths.download().resolve(name + ".poly");
            Files.createDirectories(target.getParent());
            Files.writeString(target, PolyFile.fromBoundingBox(bbox.box(), name));
            return target;
        }
        URI url = ((PolySource.Remote) job.poly()).url();
        Path target = downloadPath(paths.download().resolve("polygons"), url);
        if (Files.exists(target) && !isValidPoly(target)) {
            Files.delete(target); // corrupt cache entry: fetch it again
        }
        if (!Files.exists(target)) {
            fetcher.download(url, target, (done, total) -> checkCancel(cancel));
        }
        if (!isValidPoly(target) && requestGeneration(url)) {
            // polygons.openstreetmap.fr builds a relation's polygon on first request of index.py.
            Files.delete(target);
            fetcher.download(url, target, (done, total) -> checkCancel(cancel));
        }
        PolyFile.parse(Files.readString(target)); // fail early on a broken polygon
        return target;
    }

    /** For a {@code .../get_poly.py?id=N} URL, asks the sibling {@code index.py?id=N} to build it. */
    private boolean requestGeneration(URI url) {
        String path = url.getPath();
        String query = url.getRawQuery();
        if (path == null || !path.endsWith("/get_poly.py") || query == null) {
            return false;
        }
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("(?:^|&)id=(\\d+)").matcher(query);
        if (!id.find()) {
            return false;
        }
        try {
            fetcher.getText(url.resolve("index.py?id=" + id.group(1)));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isValidPoly(Path file) {
        try {
            PolyFile.parse(Files.readString(file));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private static ProgressListener progress(GenerationListener listener, CancelToken cancel) {
        return (done, total) -> {
            checkCancel(cancel);
            listener.onProgress(total > 0 ? (double) done / total : -1);
            listener.onDownload(done, total);
        };
    }

    private static void checkCancel(CancelToken cancel) {
        if (cancel.isCancelled()) {
            throw new CancellationException();
        }
    }

    /**
     * Local path mirroring {@code <host>/<path>} below {@code base}. Segments that could escape
     * {@code base} ("..", ".", empty) are dropped, so the result always stays inside it.
     */
    static Path downloadPath(Path base, URI url) {
        Path result = base.resolve(safeSegment(url.getHost() == null ? "unknown-host" : url.getHost()));
        String path = url.getPath() == null ? "" : url.getPath();
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty() && !segment.equals(".") && !segment.equals("..")) {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) {
            segments.add("download");
        }
        // Keep files served by query string apart, e.g. get_poly.py?id=1 vs get_poly.py?id=2.
        String query = url.getRawQuery();
        if (query != null && !query.isEmpty()) {
            int last = segments.size() - 1;
            segments.set(last, segments.get(last) + "_" + query.replace('&', '_'));
        }
        for (String segment : segments) {
            result = result.resolve(safeSegment(segment));
        }
        return result;
    }

    private static String safeSegment(String segment) {
        String cleaned = segment.replaceAll("[\\\\/:*?\"<>|\\x00]", "_");
        return cleaned.equals("..") || cleaned.equals(".") || cleaned.isBlank() ? "_" : cleaned;
    }

}
