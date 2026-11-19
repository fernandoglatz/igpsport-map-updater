package io.github.fernandoglatz.igpsmap.pipeline;

import io.github.fernandoglatz.igpsmap.matching.MapJob;

/** Progress callbacks from {@link GenerationService}; called on the worker thread. */
public interface GenerationListener {

    void onJobStart(int index, int total, MapJob job);

    void onStep(String step);

    /** Fraction of the current step, 0..1, or -1 when unknown. */
    void onProgress(double fraction);

    void onLog(String line);

    /** Bytes downloaded so far for the current file; {@code total} is -1 when unknown. */
    default void onDownload(long done, long total) {
    }

    void onJobDone(JobResult result);
}
