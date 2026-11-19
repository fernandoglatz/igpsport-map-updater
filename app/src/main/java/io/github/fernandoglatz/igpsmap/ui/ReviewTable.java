package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.matching.Confidence;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTreeTableCell;

/** Detected maps grouped by country, with a checkbox per row and per country. */
final class ReviewTable extends TreeTableView<JobRow> {

    private final TreeItem<JobRow> rootItem = new TreeItem<>();
    private Runnable onSelectionChanged = () -> { };
    private UnaryOperator<MapJob> editor = UnaryOperator.identity();

    ReviewTable() {
        setRoot(rootItem);
        setShowRoot(false);
        setEditable(true);
        setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        setPlaceholder(new javafx.scene.control.Label("Pick a device or folder with IGPSPORT .map files."));

        TreeTableColumn<JobRow, Boolean> check = new TreeTableColumn<>("");
        check.setCellValueFactory(c -> c.getValue().getValue().selected);
        check.setCellFactory(CheckBoxTreeTableCell.forTreeTableColumn(check));
        check.setEditable(true);
        check.setPrefWidth(70);
        check.setMinWidth(70);
        check.setMaxWidth(90);

        TreeTableColumn<JobRow, String> file = text("Original file", r -> r.isGroup()
                ? countryLabel(r) : r.job.get().map().path().getFileName().toString());
        file.setPrefWidth(260);
        TreeTableColumn<JobRow, String> date = text("Map date", r -> r.isGroup() ? "" : mapDate(r.job.get()));
        date.setPrefWidth(95);
        date.setMinWidth(85);
        date.setMaxWidth(110);
        TreeTableColumn<JobRow, String> poly = text("Polygon", r -> r.isGroup() || r.job.get().poly() == null
                ? "" : r.job.get().poly().label());
        TreeTableColumn<JobRow, String> pbf = text("PBF extract", r -> r.isGroup() || r.job.get().pbfLabel() == null
                ? "" : r.job.get().pbfLabel());
        TreeTableColumn<JobRow, String> confidence = text("Confidence", ReviewTable::confidenceLabel);
        confidence.setCellFactory(col -> new TreeTableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeIf(s -> s.startsWith("confidence-"));
                JobRow row = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (row == null || row.isGroup()) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                MapJob job = row.job.get();
                setText(item);
                getStyleClass().add("confidence-" + job.confidence().name().toLowerCase(Locale.ROOT));
                setTooltip(job.error() == null ? null : new Tooltip(job.error()));
            }
        });

        getColumns().addAll(List.of(check, file, date, poly, pbf, confidence));

        setRowFactory(tv -> {
            TreeTableRow<JobRow> row = new TreeTableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && row.getItem() != null && !row.getItem().isGroup()) {
                    edit(row.getTreeItem());
                }
            });
            return row;
        });
    }

    void setOnSelectionChanged(Runnable listener) {
        this.onSelectionChanged = listener;
    }

    void setEditor(UnaryOperator<MapJob> editor) {
        this.editor = editor;
    }

    void setJobs(List<MapJob> jobs) {
        rootItem.getChildren().clear();
        Map<String, TreeItem<JobRow>> groups = new LinkedHashMap<>();
        for (MapJob job : jobs) {
            TreeItem<JobRow> group = groups.computeIfAbsent(job.map().name().countryCode(), cc -> {
                TreeItem<JobRow> g = new TreeItem<>(JobRow.group(cc));
                g.setExpanded(true);
                return g;
            });
            JobRow row = JobRow.job(job);
            row.selected.addListener((obs, was, now) -> {
                if (bulkUpdate) {
                    return; // setAllSelected syncs and notifies once at the end
                }
                syncGroup(group);
                onSelectionChanged.run();
            });
            group.getChildren().add(new TreeItem<>(row));
        }
        for (TreeItem<JobRow> group : groups.values()) {
            syncGroup(group);
            group.getValue().selected.addListener((obs, was, now) -> {
                if (syncing) {
                    return;
                }
                for (TreeItem<JobRow> child : group.getChildren()) {
                    if (child.getValue().job.get().runnable()) {
                        child.getValue().selected.set(now);
                    }
                }
            });
            rootItem.getChildren().add(group);
        }
        onSelectionChanged.run();
    }

    /** Selected jobs, in display order. */
    List<MapJob> selectedJobs() {
        List<MapJob> out = new ArrayList<>();
        for (TreeItem<JobRow> group : rootItem.getChildren()) {
            for (TreeItem<JobRow> child : group.getChildren()) {
                if (child.getValue().selected.get() && child.getValue().job.get().runnable()) {
                    out.add(child.getValue().job.get());
                }
            }
        }
        return out;
    }

    void setAllSelected(boolean selected) {
        List<JobRow> rows = new ArrayList<>();
        rootItem.getChildren().forEach(group -> group.getChildren().forEach(child -> rows.add(child.getValue())));
        bulkUpdate = true;
        try {
            JobRow.setAll(rows, selected);
        } finally {
            bulkUpdate = false;
        }
        rootItem.getChildren().forEach(this::syncGroup);
        onSelectionChanged.run();
    }

    private boolean bulkUpdate;

    /** Every job (including unchecked and edited ones), in display order. */
    List<MapJob> allJobs() {
        List<MapJob> out = new ArrayList<>();
        for (TreeItem<JobRow> group : rootItem.getChildren()) {
            for (TreeItem<JobRow> child : group.getChildren()) {
                out.add(child.getValue().job.get());
            }
        }
        return out;
    }

    /** Expands the job's country, selects its row and scrolls to it. */
    void reveal(MapJob job) {
        String key = job.map().name().regionKey();
        for (TreeItem<JobRow> group : rootItem.getChildren()) {
            for (TreeItem<JobRow> child : group.getChildren()) {
                if (child.getValue().job.get().map().name().regionKey().equals(key)) {
                    group.setExpanded(true);
                    getSelectionModel().select(child);
                    int row = getRow(child);
                    scrollTo(Math.max(0, row - 3));
                    requestFocus();
                    return;
                }
            }
        }
    }

    /** Opens the editor for the selected row, if any. */
    void editSelected() {
        TreeItem<JobRow> item = getSelectionModel().getSelectedItem();
        if (item != null && !item.getValue().isGroup()) {
            edit(item);
        }
    }

    private boolean syncing;

    private void syncGroup(TreeItem<JobRow> group) {
        boolean all = JobRow.allSelected(group.getChildren().stream().map(TreeItem::getValue).toList());
        syncing = true;
        group.getValue().selected.set(all);
        syncing = false;
        refresh();
    }

    private void edit(TreeItem<JobRow> item) {
        MapJob current = item.getValue().job.get();
        MapJob edited = editor.apply(current);
        if (edited != null && edited != current) {
            item.getValue().job.set(edited);
            item.getValue().selected.set(edited.runnable());
            refresh();
            onSelectionChanged.run();
        }
    }

    private String countryLabel(JobRow group) {
        long count = rootItem.getChildren().stream()
                .filter(g -> g.getValue() == group).mapToLong(g -> g.getChildren().size()).sum();
        return group.country + "  (" + count + (count == 1 ? " file)" : " files)");
    }

    /** The date in the original file name, e.g. "2023-03-10". */
    static String mapDate(MapJob job) {
        return job.map().name().mapDate().map(java.time.LocalDate::toString).orElse(job.map().name().date());
    }

    private static String confidenceLabel(JobRow row) {
        if (row.isGroup()) {
            return "";
        }
        MapJob job = row.job.get();
        Confidence c = job.confidence();
        return switch (c) {
            case CSV -> "● from maps.csv";
            case MANUAL -> "● edited";
            case HIGH, MEDIUM, LOW -> (c == Confidence.HIGH ? "● " : "◐ ") + Math.round(job.score() * 100) + "%";
            case FALLBACK -> "◌ bounding box";
            case UNMATCHED -> "✕ unmatched";
        };
    }

    private static TreeTableColumn<JobRow, String> text(String title, java.util.function.Function<JobRow, String> value) {
        TreeTableColumn<JobRow, String> column = new TreeTableColumn<>(title);
        column.setCellValueFactory(c -> {
            JobRow row = c.getValue().getValue();
            ObservableValue<String> v = new ReadOnlyStringWrapper(value.apply(row));
            return v;
        });
        column.setEditable(false);
        return column;
    }
}
