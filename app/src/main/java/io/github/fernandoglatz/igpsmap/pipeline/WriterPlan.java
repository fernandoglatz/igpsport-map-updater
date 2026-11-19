package io.github.fernandoglatz.igpsmap.pipeline;

import java.util.Locale;

/**
 * How one Mapsforge writer run is configured: writer {@code type} ("ram" or "hd"), thread count and
 * Java heap in MiB. {@code reason} names the rule that produced it, for the log.
 */
public record WriterPlan(String type, int threads, long xmsMb, long xmxMb, String reason) {

    private static final double MIB = 1024.0 * 1024;

    /** One log line, e.g. "Writer: ram, 2 threads, heap 2048m-13107m (PBF 210 MB, RAM 16.0 GB, auto)". */
    public String describe(long pbfBytes, long totalRamBytes) {
        return String.format(Locale.ROOT, "Writer: %s, %d %s, heap %dm-%dm (PBF %d MB, RAM %.1f GB, %s)",
                type, threads, threads == 1 ? "thread" : "threads", xmsMb, xmxMb,
                Math.round(pbfBytes / MIB), totalRamBytes / MIB / 1024, reason);
    }
}
