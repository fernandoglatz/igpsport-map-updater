package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.matching.Confidence;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import io.github.fernandoglatz.igpsmap.matching.PolySource;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobRowTest {

    private static JobRow row(String region, boolean runnable) {
        String file = "BR" + region + "2601122B83FO00N00E.map";
        MapJob job = new MapJob(new DetectedMap(Path.of(file), MapFileName.parse(file).orElseThrow()),
                runnable ? new PolySource.Remote(URI.create("https://o/x.poly"), "x") : null,
                runnable ? URI.create("https://g/x.pbf") : null, "x",
                runnable ? Confidence.HIGH : Confidence.UNMATCHED, 1, List.of(), List.of(), null);
        return JobRow.job(job);
    }

    @Test
    void groupIsCheckedWhenAllRunnableRowsAreChecked() {
        assertTrue(JobRow.allSelected(List.of(row("0100", true), row("0200", false))));
    }

    @Test
    void groupIsUncheckedWhenARunnableRowIsUnchecked() {
        JobRow a = row("0100", true);
        a.selected.set(false);
        assertFalse(JobRow.allSelected(List.of(a, row("0200", true))));
    }

    @Test
    void checkAllChecksOnlyRunnableRows() {
        JobRow ok = row("0100", true);
        ok.selected.set(false);
        JobRow unmatched = row("0200", false);

        JobRow.setAll(List.of(ok, unmatched), true);

        assertTrue(ok.selected.get());
        assertFalse(unmatched.selected.get());
    }

    @Test
    void uncheckAllUnchecksEverything() {
        JobRow a = row("0100", true);
        JobRow b = row("0200", true);
        JobRow.setAll(List.of(a, b), false);
        assertFalse(a.selected.get() || b.selected.get());
    }

    @Test
    void groupWithOnlyUnmatchedRowsIsUnchecked() {
        assertFalse(JobRow.allSelected(List.of(row("0100", false))));
    }
}
