package io.github.fernandoglatz.igpsmap.ui;

import java.util.Locale;

/**
 * Turns byte counts into "123.4 MB of 1,912.0 MB (6%) · 8.2 MB/s". Speed is the average since
 * the download (or its resume) started and appears after one second. Not thread-safe.
 */
final class DownloadMeter {

    private static final double MB = 1024.0 * 1024.0;
    private static final long SECOND_NANOS = 1_000_000_000L;

    private long startNanos;
    private long startBytes;
    private long lastDone = -1;

    String update(long done, long total, long nowNanos) {
        if (lastDone < 0 || done < lastDone) {
            startNanos = nowNanos;
            startBytes = done;
        }
        lastDone = done;

        StringBuilder text = new StringBuilder(mb(done));
        if (total > 0) {
            text.append(" of ").append(mb(total)).append(" (").append(done * 100 / total).append("%)");
        }
        long elapsed = nowNanos - startNanos;
        if (elapsed >= SECOND_NANOS) {
            double speed = (done - startBytes) / MB / (elapsed / (double) SECOND_NANOS);
            text.append(" · ").append(String.format(Locale.ROOT, "%,.1f MB/s", speed));
        }
        return text.toString();
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%,.1f MB", bytes / MB);
    }
}
