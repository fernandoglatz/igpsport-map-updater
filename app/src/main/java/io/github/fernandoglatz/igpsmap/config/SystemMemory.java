package io.github.fernandoglatz.igpsmap.config;

import java.lang.management.ManagementFactory;

/** Installed physical memory, behind an interface so tests can pick a value. */
@FunctionalInterface
public interface SystemMemory {

    /** Assumed when the runtime cannot tell (e.g. the jdk.management module is missing). */
    long FALLBACK_BYTES = 8L * 1024 * 1024 * 1024;

    long totalBytes();

    /** Whether {@link #totalBytes()} is a real reading or {@link #FALLBACK_BYTES}. */
    default boolean detected() {
        return true;
    }

    static SystemMemory system() {
        long total;
        try {
            total = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize();
        } catch (RuntimeException | LinkageError e) {
            total = -1;
        }
        if (total > 0) {
            long value = total;
            return () -> value;
        }
        return new SystemMemory() {
            @Override
            public long totalBytes() {
                return FALLBACK_BYTES;
            }

            @Override
            public boolean detected() {
                return false;
            }
        };
    }
}
