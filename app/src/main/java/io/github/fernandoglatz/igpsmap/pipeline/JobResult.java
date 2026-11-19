package io.github.fernandoglatz.igpsmap.pipeline;

import io.github.fernandoglatz.igpsmap.matching.MapJob;
import java.nio.file.Path;

/** Outcome of one job. {@code output} is set only on SUCCESS. */
public record JobResult(MapJob job, Status status, Path output, String message) {

    public enum Status { SUCCESS, FAILED, SKIPPED, CANCELLED }
}
