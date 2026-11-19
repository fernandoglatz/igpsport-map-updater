package io.github.fernandoglatz.igpsmap.pipeline;

/**
 * Chooses the Mapsforge writer mode, threads and heap from the PBF size and installed memory.
 * The RAM writer is much faster than the disk ("hd") one but needs the whole extract in memory, so
 * the heap is capped at 80% of the installed RAM. {@code threads} and {@code maxHeapGb} arguments are
 * user settings where 0 means automatic.
 */
public final class WriterTuning {

    private static final long MIB = 1024L * 1024;
    /** Below this cap the automatic profile gives the whole capped heap to a single thread. */
    private static final long MIN_RAM_HEAP_MB = 4096;
    private static final long HD_XMS_MB = 2048;
    private static final long HD_MAX_XMX_MB = 8192;

    private WriterTuning() {
    }

    public static WriterPlan plan(long pbfBytes, long totalRamBytes, int threads, int maxHeapGb) {
        long capMb = capMb(totalRamBytes);
        int autoThreads;
        long preferredXmsMb;
        if (pbfBytes <= 350 * MIB) {
            autoThreads = 2;
            preferredXmsMb = 2048;
        } else if (pbfBytes <= 700 * MIB) {
            autoThreads = 1;
            preferredXmsMb = 3072;
        } else if (pbfBytes <= 1024 * MIB) {
            autoThreads = 1;
            preferredXmsMb = 6144;
        } else {
            autoThreads = 1;
            preferredXmsMb = 8192;
        }
        String reason = "auto";
        long xmxMb;
        if (maxHeapGb > 0) {
            xmxMb = maxHeapGb * 1024L;
            reason = "manual memory";
        } else if (capMb < MIN_RAM_HEAP_MB) {
            xmxMb = capMb;
            preferredXmsMb = capMb;
            autoThreads = 1;
            reason = "auto, limited by installed RAM";
        } else {
            xmxMb = capMb;
        }
        int effectiveThreads = threads > 0 ? threads : autoThreads;
        return new WriterPlan("ram", effectiveThreads, Math.min(preferredXmsMb, xmxMb), xmxMb, reason);
    }

    /** The disk-mode retry after a failed RAM run. */
    public static WriterPlan fallback(long totalRamBytes, int threads, int maxHeapGb) {
        long xmxMb = maxHeapGb > 0 ? maxHeapGb * 1024L : Math.min(HD_MAX_XMX_MB, capMb(totalRamBytes));
        return new WriterPlan("hd", threads > 0 ? threads : 1, Math.min(HD_XMS_MB, xmxMb), xmxMb, "disk-mode retry");
    }

    /** 80% of the installed RAM in whole MiB. */
    static long capMb(long totalRamBytes) {
        return totalRamBytes / MIB * 4 / 5;
    }
}
