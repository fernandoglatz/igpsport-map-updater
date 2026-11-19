package io.github.fernandoglatz.igpsmap.net;

/**
 * Download progress callback. {@code total} is -1 when unknown. Implementations may throw
 * {@link java.util.concurrent.CancellationException} to abort the transfer.
 */
@FunctionalInterface
public interface ProgressListener {

    void onProgress(long done, long total);
}
