package io.github.fernandoglatz.igpsmap.pipeline;

import io.github.fernandoglatz.igpsmap.matching.MapJob;
import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of one job. {@code output} is set only on SUCCESS. {@code warning} is set when something
 * went wrong along the way that the user should know about (e.g. memory mode failed), else null.
 */
public record JobResult(MapJob job, Status status, Path output, String message, String warning) {

    public enum Status { SUCCESS, FAILED, SKIPPED, CANCELLED }

    public JobResult(MapJob job, Status status, Path output, String message) {
        this(job, status, output, message, null);
    }

    /** One line per result with a warning: "<original file>: <warning>". */
    public static List<String> warnings(List<JobResult> results) {
        return results.stream()
                .filter(r -> r.warning() != null)
                .map(r -> r.job().map().path().getFileName() + ": " + r.warning())
                .toList();
    }
}
