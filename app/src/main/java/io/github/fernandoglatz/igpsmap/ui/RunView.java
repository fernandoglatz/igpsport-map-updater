package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.matching.MapJob;
import io.github.fernandoglatz.igpsmap.pipeline.GenerationListener;
import io.github.fernandoglatz.igpsmap.pipeline.JobResult;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Generation progress: current job, step, log and a final summary. */
final class RunView extends VBox implements GenerationListener {

    private static final int MAX_LOG_LINES = 5000;

    private final Label overall = new Label();
    private final Label step = new Label();
    private final Label detail = new Label();
    private final DownloadMeter meter = new DownloadMeter();
    /** Latest byte counts not yet shown; only one UI update is queued at a time. */
    private final java.util.concurrent.atomic.AtomicReference<long[]> pendingBytes =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final ProgressBar overallBar = new ProgressBar(0);
    private final ProgressBar stepBar = new ProgressBar(0);
    private final TextArea log = new TextArea();
    private final ListView<String> summary = new ListView<>();
    final Button cancel = new Button("Cancel");
    final Button back = new Button("Back to review");
    final Button openOutput = new Button("Open output folder");
    private int logLines;
    private int total;

    RunView() {
        setSpacing(8);
        setPadding(new Insets(12));
        log.setEditable(false);
        log.getStyleClass().add("log");
        overallBar.setMaxWidth(Double.MAX_VALUE);
        stepBar.setMaxWidth(Double.MAX_VALUE);
        summary.setPrefHeight(140);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, cancel, spacer, openOutput, back);
        VBox.setVgrow(log, Priority.ALWAYS);
        detail.getStyleClass().add("hint");
        getChildren().addAll(overall, overallBar, step, stepBar, detail, log, new Label("Summary"), summary, buttons);
    }

    void reset(int jobCount) {
        total = jobCount;
        logLines = 0;
        log.clear();
        summary.getItems().clear();
        overall.setText("Starting…");
        step.setText("");
        detail.setText("");
        overallBar.setProgress(0);
        stepBar.setProgress(0);
        cancel.setDisable(false);
        back.setDisable(true);
    }

    void finished(List<JobResult> results) {
        long ok = results.stream().filter(r -> r.status() == JobResult.Status.SUCCESS).count();
        int warnings = JobResult.warnings(results).size();
        overall.setText("Done: " + ok + " of " + results.size() + " maps generated."
                + (warnings > 0 ? " ⚠ Memory mode failed for " + warnings + (warnings == 1 ? " map." : " maps.") : ""));
        step.setText("");
        detail.setText("");
        overallBar.setProgress(1);
        stepBar.setProgress(1);
        cancel.setDisable(true);
        back.setDisable(false);
    }

    void failed(Throwable error) {
        overall.setText("Generation stopped: " + error.getMessage());
        cancel.setDisable(true);
        back.setDisable(false);
    }

    @Override
    public void onJobStart(int index, int count, MapJob job) {
        Platform.runLater(() -> {
            overall.setText("Map " + index + " of " + count + ": " + job.map().path().getFileName());
            overallBar.setProgress((index - 1.0) / Math.max(1, total));
        });
    }

    @Override
    public void onStep(String text) {
        Platform.runLater(() -> {
            step.setText(text);
            detail.setText("");
        });
    }

    @Override
    public void onDownload(long done, long total) {
        if (pendingBytes.getAndSet(new long[] {done, total, System.nanoTime()}) == null) {
            Platform.runLater(() -> {
                long[] latest = pendingBytes.getAndSet(null);
                if (latest != null) {
                    detail.setText(meter.update(latest[0], latest[1], latest[2]));
                }
            });
        }
    }

    /** Latest progress not yet shown; only one UI update is queued at a time. */
    private final java.util.concurrent.atomic.AtomicReference<Double> pendingProgress =
            new java.util.concurrent.atomic.AtomicReference<>();

    @Override
    public void onProgress(double fraction) {
        if (pendingProgress.getAndSet(fraction) == null) {
            Platform.runLater(() -> {
                Double latest = pendingProgress.getAndSet(null);
                if (latest != null) {
                    stepBar.setProgress(latest < 0 ? ProgressBar.INDETERMINATE_PROGRESS : latest);
                }
            });
        }
    }

    @Override
    public void onLog(String line) {
        Platform.runLater(() -> appendLog(line));
    }

    @Override
    public void onJobDone(JobResult result) {
        Platform.runLater(() -> {
            String icon = switch (result.status()) {
                case SUCCESS -> result.warning() != null ? "⚠" : "✔";
                case FAILED -> "✕";
                case SKIPPED -> "–";
                case CANCELLED -> "■";
            };
            summary.getItems().add(icon + " " + result.job().map().path().getFileName() + " — " + result.message()
                    + (result.warning() != null ? " ⚠ " + result.warning() : ""));
            appendLog("[" + result.status() + "] " + result.message());
        });
    }

    private void appendLog(String line) {
        log.appendText(line + "\n");
        if (++logLines > MAX_LOG_LINES) {
            String text = log.getText();
            int cut = text.indexOf('\n', text.length() / 5);
            if (cut > 0) {
                log.deleteText(0, cut + 1);
                logLines = (int) log.getText().chars().filter(c -> c == '\n').count();
            }
        }
    }
}
