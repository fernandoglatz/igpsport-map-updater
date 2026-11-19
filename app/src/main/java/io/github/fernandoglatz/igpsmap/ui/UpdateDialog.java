package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.pipeline.CancelToken;
import io.github.fernandoglatz.igpsmap.update.PackageType;
import io.github.fernandoglatz.igpsmap.update.ReleaseInfo;
import io.github.fernandoglatz.igpsmap.update.UpdateInstaller;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Shows a newer release with its notes and a link to the releases page. "Update" downloads the file
 * for this installation and hands it to the installer; the result tells the main window what to do next.
 */
final class UpdateDialog extends Dialog<UpdateDialog.Outcome> {

    /** The user's choice: SKIP the version, or the installer hand-off after an update. */
    sealed interface Outcome permits Skip, Installed {
    }

    record Skip() implements Outcome {
    }

    record Installed(UpdateInstaller.Handoff handoff) implements Outcome {
    }

    private static final ButtonType UPDATE = new ButtonType("Update", ButtonBar.ButtonData.OK_DONE);
    private static final ButtonType SKIP = new ButtonType("Skip this version", ButtonBar.ButtonData.LEFT);
    private static final ButtonType LATER = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);

    private final Label status = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private Task<UpdateInstaller.Handoff> task;
    private CancelToken cancel;

    UpdateDialog(Window owner, ReleaseInfo release, String currentVersion, Optional<ReleaseInfo.Asset> asset,
                 PackageType packageType, UpdateInstaller installer, Consumer<String> openUrl) {
        initOwner(owner);
        setTitle("Update available");
        setHeaderText("IGPSPORT Map Updater " + release.version() + " is available");
        setResizable(true);
        if (asset.isPresent()) {
            getDialogPane().getButtonTypes().add(UPDATE);
        }
        getDialogPane().getButtonTypes().addAll(SKIP, LATER);

        Label current = new Label("You have version " + currentVersion + ". "
                + asset.map(a -> "Update downloads " + a.name() + " (" + CatalogSelection.size(a.size()) + ")"
                        + afterDownload(packageType)).orElse("Download the new version from the releases page."));
        current.setWrapText(true);
        current.setMinHeight(Region.USE_PREF_SIZE);
        TextArea notes = new TextArea(release.notes().isBlank() ? "No release notes." : release.notes());
        notes.setEditable(false);
        notes.setWrapText(true);
        notes.setPrefRowCount(10);
        VBox.setVgrow(notes, Priority.ALWAYS);
        Hyperlink releases = new Hyperlink("Open the releases page");
        releases.setOnAction(e -> openUrl.accept(release.releasesPage().toString()));
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        status.getStyleClass().add("hint");
        status.setWrapText(true);
        VBox content = new VBox(8, current, new Label("What's new:"), notes, releases, progress, status);
        content.setPadding(new Insets(8, 12, 4, 12));
        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(560);

        setResultConverter(button -> button == SKIP ? new Skip() : null);
        if (asset.isPresent()) {
            Node update = getDialogPane().lookupButton(UPDATE);
            update.addEventFilter(ActionEvent.ACTION, e -> {
                e.consume(); // stay open while downloading
                start(asset.get(), packageType, installer);
            });
        }
        Node later = getDialogPane().lookupButton(LATER);
        later.addEventFilter(ActionEvent.ACTION, e -> {
            if (task != null && task.isRunning()) {
                e.consume();
                cancel.cancel();
                status.setText("Cancelling…");
            }
        });
        setOnCloseRequest(e -> {
            if (task != null && task.isRunning()) {
                cancel.cancel();
            }
        });
    }

    /** What Update does once the file is downloaded, for the dialog text. */
    static String afterDownload(PackageType type) {
        return switch (type) {
            case MSI -> " and starts the installer.";
            case DMG -> " and opens it so you can drag the app to Applications.";
            case DEB -> " and installs it (your password is needed).";
            case APP_IMAGE -> ", installs it and restarts the app.";
            case OTHER, SOURCE -> ".";
        };
    }

    private void start(ReleaseInfo.Asset asset, PackageType type, UpdateInstaller installer) {
        CancelToken token = new CancelToken();
        cancel = token;
        task = new Task<>() {
            @Override
            protected UpdateInstaller.Handoff call() throws Exception {
                token.bind(Thread.currentThread());
                try {
                    updateMessage("Downloading " + asset.name() + "…");
                    Path file = installer.download(asset, (done, total) -> {
                        if (token.isCancelled()) {
                            throw new CancellationException();
                        }
                        if (total > 0) {
                            updateProgress(done, total);
                            updateMessage("Downloading " + asset.name() + " · " + CatalogSelection.size(done)
                                    + " of " + CatalogSelection.size(total));
                        }
                    });
                    if (token.isCancelled()) {
                        throw new CancellationException();
                    }
                    updateProgress(-1, 1);
                    updateMessage(switch (type) {
                        case DEB -> "Installing — enter your password if asked…";
                        case APP_IMAGE -> "Installing…";
                        default -> "Opening the installer…";
                    });
                    return installer.handOff(file, type);
                } finally {
                    token.unbind();
                    Thread.interrupted();
                }
            }
        };
        setButtonsDisabled(true);
        progress.setVisible(true);
        progress.progressProperty().bind(task.progressProperty());
        status.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            setResult(new Installed(task.getValue()));
            close();
        });
        task.setOnFailed(e -> {
            status.textProperty().unbind();
            progress.progressProperty().unbind();
            progress.setVisible(false);
            setButtonsDisabled(false);
            Throwable error = task.getException();
            status.setText(error instanceof CancellationException || token.isCancelled()
                    ? "Download cancelled." : "Update failed: " + error.getMessage());
        });
        Thread thread = new Thread(task, "app-update");
        thread.setDaemon(true);
        thread.start();
    }

    /** Update and Skip are off during the download; Later stays on to cancel it. */
    private void setButtonsDisabled(boolean disabled) {
        for (ButtonType type : new ButtonType[] {UPDATE, SKIP}) {
            Node button = getDialogPane().lookupButton(type);
            if (button != null) {
                button.setDisable(disabled);
            }
        }
    }
}
