package io.github.fernandoglatz.igpsmap.pipeline;

import io.github.fernandoglatz.igpsmap.config.AppPaths;
import io.github.fernandoglatz.igpsmap.config.Settings;
import io.github.fernandoglatz.igpsmap.config.SystemMemory;
import io.github.fernandoglatz.igpsmap.io.PbfDateReader;
import io.github.fernandoglatz.igpsmap.io.PolyFile;
import io.github.fernandoglatz.igpsmap.io.Igs630MapCompatibility;
import io.github.fernandoglatz.igpsmap.io.MapMd5List;
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
    private final SystemMemory memory;

    public GenerationService(HttpFetcher fetcher, ProcessRunner runner, AppPaths paths, Settings settings,
                             Path javaExe, String classpath, List<Path> tagOverrideDirs, SystemMemory memory) {
        this.fetcher = fetcher;
        this.runner = runner;
        this.paths = paths;
        this.settings = settings;
        this.javaExe = javaExe;
        this.classpath = classpath;
        this.tagOverrideDirs = List.copyOf(tagOverrideDirs);
        this.memory = memory;
    }

    public List<JobResult> runAll(List<MapJob> jobs, GenerationListener listener, CancelToken cancel) {
        cancel.bind(Thread.currentThread());
        try {
            List<JobResult> results = runJobs(jobs, listener, cancel);
            if (settings.generateMd5() && results.stream().anyMatch(r -> r.status() == JobResult.Status.SUCCESS)) {
                try {
                    if (cancel.isCancelled()) throw new CancellationException("Generation cancelled; MD5 list was not updated");
                    listener.onStep("Updating map_md5_list.cfg");
                    List<Path> references = jobs.stream().map(j -> j.map().path().toAbsolutePath().getParent()
                            .resolve(MapMd5List.FILE_NAME)).distinct().toList();
                    Path md5 = MapMd5List.write(settings.outputDir(), references, cancel::isCancelled);
                    listener.onLog("Updated checksums: " + md5);
                } catch (IOException | CancellationException e) {
                    String warning = "Could not update map_md5_list.cfg: " + e.getMessage();
                    listener.onLog("WARNING: " + warning);
                    for (int i = results.size() - 1; i >= 0; i--) {
                        JobResult result = results.get(i);
                        if (result.status() == JobResult.Status.SUCCESS) {
                            results.set(i, new JobResult(result.job(), result.status(), result.output(), result.message(),
                                    result.warning() == null ? warning : result.warning() + " " + warning));
                            break;
                        }
                    }
                }
            }
            return results;
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

            if (settings.igs630Compatibility()) {
                // Fail before large downloads if the selected template is not a complete map.
                Igs630MapCompatibility.readCreatedBy(job.map().path());
                listener.onLog("Map profile: iGS630 compatibility");
            } else {
                listener.onLog("Map profile: enhanced");
            }

            listener.onStep("Downloading PBF");
            Path pbf = ensurePbf(job.pbfUrl(), listener, cancel);

            listener.onStep("Preparing polygon");
            Path poly = ensurePoly(job, cancel);

            TagFiles tags = TagConfig.resolve(tagOverrideDirs, paths.tmp(), settings.igs630Compatibility());
            listener.onStep("Running osmosis");
            listener.onProgress(-1);
            long pbfBytes = Files.size(pbf);
            long ramBytes = memory.totalBytes();
            if (!memory.detected()) {
                listener.onLog("Could not detect installed memory; assuming " + ramBytes / (1024 * 1024 * 1024) + " GB");
            }
            WriterPlan plan = WriterTuning.plan(pbfBytes, ramBytes, settings.threads(), settings.maxHeapGb());
            int exit = runWriter(plan, pbfBytes, ramBytes, pbf, poly, tags, tmpMap, listener, cancel);
            String warning = null;
            if (exit != 0 && "ram".equals(plan.type())) {
                int ramExit = exit;
                if (settings.hdFallback()) {
                    listener.onLog("RAM writer failed (exit " + ramExit + "); retrying in disk mode (hd)");
                    Files.deleteIfExists(tmpMap);
                    WriterPlan fallback = WriterTuning.fallback(ramBytes, settings.threads(), settings.maxHeapGb());
                    exit = runWriter(fallback, pbfBytes, ramBytes, pbf, poly, tags, tmpMap, listener, cancel);
                    warning = exit == 0
                            ? "Memory mode failed (exit " + ramExit + "); generated in disk mode instead."
                            : "Memory mode failed (exit " + ramExit + ") and the disk-mode retry failed too.";
                } else {
                    warning = "Memory mode failed (exit " + ramExit + "). Turn on \"Retry in disk mode if memory runs out\" "
                            + "or raise Max memory.";
                }
                listener.onLog("WARNING: " + warning);
            }
            if (exit != 0) {
                return new JobResult(job, JobResult.Status.FAILED, null, "osmosis failed with exit code " + exit, warning);
            }
            if (!Files.exists(tmpMap)) {
                return new JobResult(job, JobResult.Status.FAILED, null, "osmosis produced no file", warning);
            }

            if (settings.igs630Compatibility()) {
                listener.onStep("Applying iGS630 map metadata");
                Igs630MapCompatibility.patch(job.map().path(), tmpMap);
            }
            listener.onStep("Renaming");
            Path output = MapRenamer.rename(tmpMap, job.map().name(), PbfDateReader.resolveDate(pbf), settings.outputDir());
            return new JobResult(job, JobResult.Status.SUCCESS, output, "Generated " + output.getFileName(), warning);
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

    /** Runs osmosis once with {@code plan}; throws CancellationException when cancelled meanwhile. */
    private int runWriter(WriterPlan plan, long pbfBytes, long ramBytes, Path pbf, Path poly, TagFiles tags,
                          Path tmpMap, GenerationListener listener, CancelToken cancel) throws IOException, InterruptedException {
        listener.onLog(plan.describe(pbfBytes, ramBytes));
        List<String> command = OsmosisCommand.build(javaExe, classpath, plan, paths.tmp(), pbf, poly, tags, tmpMap);
        int exit = runner.run(command, listener::onLog, cancel);
        if (cancel.isCancelled()) {
            throw new CancellationException();
        }
        return exit;
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
