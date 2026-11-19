package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DownloadMeterTest {

    private static final long MB = 1024 * 1024;
    private static final long SECOND = 1_000_000_000L;

    @Test
    void showsSizeAndPercentWithoutSpeedDuringFirstSecond() {
        DownloadMeter meter = new DownloadMeter();
        assertEquals("0.0 MB of 10.0 MB (0%)", meter.update(0, 10 * MB, 0));
        assertEquals("5.0 MB of 10.0 MB (50%)", meter.update(5 * MB, 10 * MB, SECOND / 2));
    }

    @Test
    void addsAverageSpeedAfterOneSecond() {
        DownloadMeter meter = new DownloadMeter();
        meter.update(0, 2000 * MB, 0);
        assertEquals("1,500.0 MB of 2,000.0 MB (75%) · 750.0 MB/s", meter.update(1500 * MB, 2000 * MB, 2 * SECOND));
    }

    @Test
    void unknownTotalShowsOnlyDownloadedSize() {
        DownloadMeter meter = new DownloadMeter();
        meter.update(0, -1, 0);
        assertEquals("12.0 MB · 6.0 MB/s", meter.update(12 * MB, -1, 2 * SECOND));
    }

    @Test
    void resumedDownloadMeasuresSpeedFromTheResumeOffset() {
        DownloadMeter meter = new DownloadMeter();
        meter.update(100 * MB, 200 * MB, 0);
        assertEquals("120.0 MB of 200.0 MB (60%) · 10.0 MB/s", meter.update(120 * MB, 200 * MB, 2 * SECOND));
    }

    @Test
    void newDownloadResetsTheMeter() {
        DownloadMeter meter = new DownloadMeter();
        meter.update(0, 100 * MB, 0);
        meter.update(100 * MB, 100 * MB, 10 * SECOND);
        // Next file starts at 0 again: speed restarts and is hidden for the first second.
        assertEquals("0.5 MB of 1.0 MB (50%)", meter.update(MB / 2, MB, 10 * SECOND + SECOND / 2));
    }
}
