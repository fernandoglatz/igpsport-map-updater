package io.github.fernandoglatz.igpsmap.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SystemMemoryTest {

    @Test
    void reportsInstalledMemory() {
        long total = SystemMemory.system().totalBytes();
        assertTrue(total >= 512L * 1024 * 1024, "unexpected total memory: " + total);
    }
}
