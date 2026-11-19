package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.matching.MapJob;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

/** One tree row: either a country group (job == null) or a map job. */
final class JobRow {

    final String country;
    final ObjectProperty<MapJob> job = new SimpleObjectProperty<>();
    final BooleanProperty selected = new SimpleBooleanProperty();

    private JobRow(String country, MapJob job, boolean selected) {
        this.country = country;
        this.job.set(job);
        this.selected.set(selected);
    }

    static JobRow group(String country) {
        return new JobRow(country, null, false);
    }

    static JobRow job(MapJob job) {
        return new JobRow(job.map().name().countryCode(), job, job.confidence().preselected() && job.runnable());
    }

    /** Country checkbox state: every runnable row is checked (unmatched rows can't be checked). */
    static boolean allSelected(java.util.List<JobRow> rows) {
        java.util.List<JobRow> runnable = rows.stream().filter(r -> r.job.get().runnable()).toList();
        return !runnable.isEmpty() && runnable.stream().allMatch(r -> r.selected.get());
    }

    /** Check all / Uncheck all: rows without a usable match are never checked. */
    static void setAll(java.util.List<JobRow> rows, boolean selected) {
        for (JobRow row : rows) {
            row.selected.set(selected && row.job.get().runnable());
        }
    }

    boolean isGroup() {
        return job.get() == null;
    }
}
