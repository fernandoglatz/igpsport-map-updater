package io.github.fernandoglatz.igpsmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.config.Settings;
import org.junit.jupiter.api.Test;

class WriterTuningTest {

    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024 * MIB;
    private static final int AUTO = Settings.AUTO;

    @Test
    void smallPbfUsesTwoThreadsAndTheCappedHeap() {
        WriterPlan plan = WriterTuning.plan(200 * MIB, 16 * GIB, AUTO, AUTO);
        assertEquals(new WriterPlan("ram", 2, 2048, 13107, plan.reason()), plan);
    }

    @Test
    void largerPbfBandsUseOneThreadAndALargerInitialHeap() {
        assertEquals(3072, WriterTuning.plan(500 * MIB, 16 * GIB, AUTO, AUTO).xmsMb());
        assertEquals(6144, WriterTuning.plan(900 * MIB, 16 * GIB, AUTO, AUTO).xmsMb());
        assertEquals(8192, WriterTuning.plan(2 * GIB, 16 * GIB, AUTO, AUTO).xmsMb());
        assertEquals(1, WriterTuning.plan(500 * MIB, 16 * GIB, AUTO, AUTO).threads());
        assertEquals(1, WriterTuning.plan(2 * GIB, 16 * GIB, AUTO, AUTO).threads());
    }

    @Test
    void bandEdgesAreInclusive() {
        assertEquals(2, WriterTuning.plan(350 * MIB, 16 * GIB, AUTO, AUTO).threads());
        assertEquals(3072, WriterTuning.plan(700 * MIB, 16 * GIB, AUTO, AUTO).xmsMb());
        assertEquals(6144, WriterTuning.plan(1024 * MIB, 16 * GIB, AUTO, AUTO).xmsMb());
    }

    @Test
    void initialHeapNeverExceedsTheCap() {
        WriterPlan plan = WriterTuning.plan(2 * GIB, 8 * GIB, AUTO, AUTO);
        assertEquals(6553, plan.xmxMb());
        assertEquals(6553, plan.xmsMb());
        assertEquals("ram", plan.type());
    }

    @Test
    void lowMemoryMachinesUseOneThreadAndAFixedHeap() {
        WriterPlan plan = WriterTuning.plan(100 * MIB, 4 * GIB, AUTO, AUTO);
        assertEquals(new WriterPlan("ram", 1, 3276, 3276, plan.reason()), plan);
        assertTrue(plan.reason().contains("limited"), plan.reason());
    }

    @Test
    void manualValuesOverrideTheAutomaticOnes() {
        WriterPlan plan = WriterTuning.plan(900 * MIB, 16 * GIB, 6, 10);
        assertEquals(new WriterPlan("ram", 6, 6144, 10240, plan.reason()), plan);
        WriterPlan small = WriterTuning.plan(2 * GIB, 16 * GIB, AUTO, 4);
        assertEquals(4096, small.xmxMb());
        assertEquals(4096, small.xmsMb());
        assertEquals(1, small.threads());
    }

    @Test
    void fallbackUsesDiskModeWithAtMostEightGigabytes() {
        WriterPlan big = WriterTuning.fallback(16 * GIB, AUTO, AUTO);
        assertEquals(new WriterPlan("hd", 1, 2048, 8192, big.reason()), big);
        assertEquals(6553, WriterTuning.fallback(8 * GIB, AUTO, AUTO).xmxMb());
        WriterPlan manual = WriterTuning.fallback(16 * GIB, 3, 12);
        assertEquals(new WriterPlan("hd", 3, 2048, 12288, manual.reason()), manual);
        WriterPlan tiny = WriterTuning.fallback(2 * GIB, AUTO, AUTO);
        assertEquals(1638, tiny.xmxMb());
        assertEquals(1638, tiny.xmsMb());
    }

    @Test
    void describesTheDecision() {
        WriterPlan plan = WriterTuning.plan(210 * MIB, 16 * GIB, AUTO, AUTO);
        assertEquals("Writer: ram, 2 threads, heap 2048m-13107m (PBF 210 MB, RAM 16.0 GB, " + plan.reason() + ")",
                plan.describe(210 * MIB, 16 * GIB));
    }
}
