package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.catalog.Product;
import io.github.fernandoglatz.igpsmap.config.Settings;
import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.device.DetectedSource;
import io.github.fernandoglatz.igpsmap.device.DeviceScanner;
import io.github.fernandoglatz.igpsmap.device.UnsupportedDevice;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import io.github.fernandoglatz.igpsmap.pipeline.CancelToken;
import io.github.fernandoglatz.igpsmap.pipeline.JobResult;
import io.github.fernandoglatz.igpsmap.update.PackageType;
import io.github.fernandoglatz.igpsmap.update.ReleaseInfo;
import io.github.fernandoglatz.igpsmap.update.UpdateInstaller;
import io.github.fernandoglatz.igpsmap.update.Version;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

/** Main window: source bar, review table, settings footer; swaps to the run view while generating. */
public final class MainApp extends Application {

    private AppController controller;
    private Stage stage;

    private final ComboBox<DetectedSource> sources = new ComboBox<>();
    private final ReviewTable table = new ReviewTable();
    private final CoverageView coverage = new CoverageView();
    private final javafx.scene.control.TabPane tabs = new javafx.scene.control.TabPane();
    private final RunView runView = new RunView();
    private final Label status = new Label("Looking for IGPSPORT devices…");
    private final Label folderLabel = new Label();
    private final Button openFolder = new Button("Open folder");
    private final ProgressBar busy = new ProgressBar();
    private final TextField outputDir = new TextField();
    private final ComboBox<Integer> threads = autoCombo(1, 16, "");
    private final ComboBox<Integer> heap = autoCombo(2, 64, " GB");
    private final CheckBox hdFallback = new CheckBox("Retry in disk mode if memory runs out");
    private final Button generate = new Button("Generate");
    private final Button edit = new Button("Edit match…");
    private final StackPane center = new StackPane();
    private final HBox updateBanner = new HBox(8);
    private BorderPane reviewPane;
    private CancelToken cancelToken;
    /** Increments per match request so results of superseded requests are ignored. */
    private int matchRequest;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        controller = AppController.createDefault(Path.of("").toAbsolutePath());
        Settings settings = controller.settings();
        outputDir.setText(PathText.display(settings.outputDir(), PathText.home()));
        selectAutoValue(threads, settings.threads());
        selectAutoValue(heap, settings.maxHeapGb());
        hdFallback.setSelected(settings.hdFallback());

        javafx.scene.control.Tab reviewTab = new javafx.scene.control.Tab("Review", reviewContent());
        javafx.scene.control.Tab coverageTab = new javafx.scene.control.Tab("Coverage", coverage);
        tabs.getTabs().addAll(reviewTab, coverageTab);
        tabs.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE);
        coverage.setOnOpen(job -> {
            tabs.getSelectionModel().select(reviewTab);
            table.reveal(job);
        });
        updateBanner.getStyleClass().add("update-banner");
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);
        reviewPane = new BorderPane(tabs, new VBox(updateBanner, sourceBar()), null, footer(), null);
        center.getChildren().addAll(reviewPane, runView);
        runView.setVisible(false);

        table.setEditor(job -> new EditJobDialog(stage, job).showAndWait().orElse(job));
        table.setOnSelectionChanged(() -> {
            updateGenerateButton();
            coverage.setModel(CoverageModel.of(table.allJobs(), table.selectedJobs()));
        });
        runView.cancel.setOnAction(e -> {
            if (cancelToken != null) {
                cancelToken.cancel();
                runView.cancel.setDisable(true);
            }
        });
        runView.back.setOnAction(e -> showReview());
        runView.openOutput.setOnAction(e -> openFolder(PathText.parse(outputDir.getText(), PathText.home())));

        Scene scene = new Scene(center, 1100, 700);
        scene.getStylesheets().add(MainApp.class.getResource("/ui/app.css").toExternalForm());
        stage.setTitle(AppInfo.windowTitle(Version.current()));
        stage.getIcons().setAll(AppIcon.images());
        stage.setScene(scene);
        stage.show();
        updateGenerateButton();
        loadBaseMap();
        detectDevices();
        checkForUpdate();
    }

    /** Asks GitHub for a newer release in the background; any failure leaves the window as it is. */
    private void checkForUpdate() {
        Task<Optional<ReleaseInfo>> task = new Task<>() {
            @Override
            protected Optional<ReleaseInfo> call() throws Exception {
                return controller.checkForUpdate();
            }
        };
        task.setOnSucceeded(e -> task.getValue().ifPresent(this::showUpdateBanner));
        Thread thread = new Thread(task, "update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void showUpdateBanner(ReleaseInfo release) {
        Label text = new Label("Version " + release.version() + " is available. You have "
                + Version.current().map(Version::toString).orElse("?") + ".");
        Button open = new Button("Update…");
        open.setDefaultButton(false);
        open.setOnAction(e -> openUpdate(release));
        Button close = new Button("✕");
        close.getStyleClass().add("update-banner-close");
        close.setTooltip(new javafx.scene.control.Tooltip("Hide until the next start"));
        close.setOnAction(e -> hideUpdateBanner());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        updateBanner.getChildren().setAll(text, open, spacer, close);
        updateBanner.setVisible(true);
        updateBanner.setManaged(true);
    }

    private void hideUpdateBanner() {
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);
    }

    private void openUpdate(ReleaseInfo release) {
        String current = Version.current().map(Version::toString).orElse("?");
        UpdateDialog dialog = new UpdateDialog(stage, release, current, controller.updateAsset(release),
                PackageType.current(), controller.updateInstaller(), url -> getHostServices().showDocument(url));
        dialog.showAndWait().ifPresent(outcome -> {
            if (outcome instanceof UpdateDialog.Skip) {
                controller.updateSettings(controller.settings().withSkippedVersion(release.version().toString()));
                hideUpdateBanner();
            } else if (outcome instanceof UpdateDialog.Installed installed) {
                afterHandoff(installed.handoff());
            }
        });
    }

    private void afterHandoff(UpdateInstaller.Handoff handoff) {
        switch (handoff.next()) {
            case EXIT_APP -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION, handoff.message());
                alert.initOwner(stage);
                alert.setHeaderText("Updating IGPSPORT Map Updater");
                alert.showAndWait();
                Platform.exit();
            }
            case RESTARTING -> {
                status.setText(handoff.message());
                Platform.exit();
                // The helper waits for this process to end; make sure it does even if a thread lingers.
                Thread exit = new Thread(() -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    System.exit(0);
                }, "exit-for-update");
                exit.setDaemon(true);
                exit.start();
            }
            case OFFER_RESTART -> {
                String appPath = System.getProperty("jpackage.app-path");
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION, handoff.message(),
                        new javafx.scene.control.ButtonType("Restart now", javafx.scene.control.ButtonBar.ButtonData.OK_DONE),
                        new javafx.scene.control.ButtonType("Later", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
                alert.initOwner(stage);
                alert.setHeaderText("Update installed");
                alert.showAndWait().filter(b -> b.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                        .ifPresent(b -> restart(appPath));
                hideUpdateBanner();
            }
            case SHOW_FOLDER -> {
                // The folder opens only when asked: launching a file manager while the message appears
                // can push the message out of sight.
                TextArea text = new TextArea(handoff.message() + "\n\nFile: " + handoff.file().toAbsolutePath());
                text.setEditable(false);
                text.setWrapText(true);
                text.setPrefRowCount(6);
                javafx.scene.control.ButtonType openFolder =
                        new javafx.scene.control.ButtonType("Open folder", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                Alert alert = new Alert(Alert.AlertType.INFORMATION, "", openFolder, javafx.scene.control.ButtonType.CLOSE);
                alert.initOwner(stage);
                alert.setTitle("Update");
                alert.setHeaderText("New version downloaded");
                alert.getDialogPane().setContent(text);
                alert.getDialogPane().setPrefWidth(620);
                alert.setResizable(true);
                alert.showAndWait().filter(openFolder::equals).ifPresent(b ->
                        openFolder(handoff.file().toAbsolutePath().getParent()));
            }
        }
    }

    /** Opens a folder in the file manager (not the browser, which HostServices uses on Windows). */
    private void openFolder(Path folder) {
        try {
            FolderOpener.open(folder);
        } catch (java.io.IOException | RuntimeException e) {
            status.setText("Could not open " + folder + ": " + e.getMessage());
        }
    }

    /** Starts the installed launcher again and closes this instance. */
    private void restart(String appPath) {
        if (appPath == null || appPath.isBlank()) {
            status.setText("Restart the app to use the new version.");
            return;
        }
        try {
            UpdateInstaller.systemStarter().start(List.of(appPath));
            Platform.exit();
        } catch (java.io.IOException e) {
            status.setText("Could not restart automatically: " + e.getMessage() + ". Restart the app to use the new version.");
        }
    }

    private VBox sourceBar() {
        sources.setPromptText("No IGPSPORT device found");
        sources.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(DetectedSource source) {
                return source == null ? "" : source.label();
            }

            @Override
            public DetectedSource fromString(String text) {
                return null;
            }
        });
        sources.setMaxWidth(Double.MAX_VALUE);
        sources.valueProperty().addListener((obs, old, source) -> {
            sources.setTooltip(source == null ? null : new javafx.scene.control.Tooltip(source.root().toString()));
            String folder = source == null ? "" : SourceFolders.describe(source.root(), source.maps());
            folderLabel.setText(folder);
            folderLabel.setTooltip(source == null ? null : new javafx.scene.control.Tooltip(folder));
            openFolder.setDisable(source == null);
        });
        sources.setOnAction(e -> {
            DetectedSource source = sources.getValue();
            if (source != null) {
                matchMaps(source.maps());
            }
        });
        Button choose = new Button("Choose folder…");
        choose.setOnAction(e -> chooseFolder());
        Button rescan = new Button("⟳ Rescan");
        rescan.setOnAction(e -> detectDevices());
        Button refresh = new Button("Refresh sources");
        refresh.setOnAction(e -> run("Clearing cached sources…", () -> {
            controller.refreshSources();
            return null;
        }, ignored -> {
            DetectedSource source = sources.getValue();
            if (source != null) {
                matchMaps(source.maps());
            }
        }));
        HBox.setHgrow(sources, Priority.ALWAYS);
        sources.setMinWidth(200);
        HBox row = new HBox(8, new Label("Source:"), sources, choose, rescan, refresh);
        row.setAlignment(Pos.CENTER_LEFT);
        keepPreferredWidth(row);

        openFolder.setDisable(true);
        openFolder.setOnAction(e -> {
            DetectedSource source = sources.getValue();
            if (source != null) {
                openFolder(source.root());
            }
        });
        Label folderTitle = new Label("Folder:");
        folderTitle.setMinWidth(Region.USE_PREF_SIZE);
        openFolder.setMinWidth(Region.USE_PREF_SIZE);
        folderLabel.setMinWidth(0);
        // Always show the full path: wrap long ones instead of shortening them.
        folderLabel.setWrapText(true);
        HBox.setHgrow(folderLabel, Priority.ALWAYS);
        folderLabel.getStyleClass().add("folder-path");
        HBox folderRow = new HBox(8, folderTitle, folderLabel, openFolder);
        folderRow.setAlignment(Pos.CENTER_LEFT);

        busy.setVisible(false);
        busy.setPrefWidth(200);
        HBox statusRow = new HBox(8, status, busy);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, row, folderRow, statusRow);
        box.setPadding(new Insets(10, 12, 6, 12));
        return box;
    }

    /** The review table with its toolbar: Check all, Uncheck all, Edit match. */
    private BorderPane reviewContent() {
        Button checkAll = new Button("Check all");
        checkAll.setOnAction(e -> table.setAllSelected(true));
        Button uncheckAll = new Button("Uncheck all");
        uncheckAll.setOnAction(e -> table.setAllSelected(false));
        edit.setOnAction(e -> table.editSelected());
        Button addMaps = new Button("Add maps…");
        addMaps.setTooltip(new javafx.scene.control.Tooltip(
                "Download original maps from the official iGPSPORT catalog, e.g. regions not on the device"));
        addMaps.setOnAction(e -> openAddMaps());
        HBox toolbar = new HBox(8, checkAll, uncheckAll, edit, addMaps);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 8, 6, 8));
        return new BorderPane(table, toolbar, null, null, null);
    }

    private VBox footer() {
        Button browse = new Button("…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Output folder");
            File dir = chooser.showDialog(stage);
            if (dir != null) {
                outputDir.setText(PathText.display(dir.toPath(), PathText.home()));
            }
        });
        threads.setMinWidth(Region.USE_PREF_SIZE);
        heap.setMinWidth(Region.USE_PREF_SIZE);
        hdFallback.setMinWidth(Region.USE_PREF_SIZE);
        hdFallback.setTooltip(new javafx.scene.control.Tooltip(
                "If the fast in-memory writer fails (e.g. out of memory), run it again in the slower disk mode"));

        generate.setDefaultButton(true);
        generate.setOnAction(e -> startGeneration());
        HBox.setHgrow(outputDir, Priority.ALWAYS);
        outputDir.setMinWidth(150);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);
        HBox row = new HBox(8, new Label("Output:"), outputDir, browse, new Label("Threads"), threads,
                new Label("Max memory"), heap, hdFallback, spacer, generate);
        row.setAlignment(Pos.CENTER_LEFT);
        keepPreferredWidth(row);
        Label hint = new Label("Rows matched with high confidence or from maps.csv start checked. "
                + "Double-click a row to change its polygon or PBF. Auto picks threads and memory from each "
                + "extract's size and your installed RAM.");
        hint.getStyleClass().add("hint");
        hint.setMinWidth(0);
        Label version = new Label(AppInfo.versionLabel(Version.current()));
        version.getStyleClass().add("hint");
        version.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(hint, Priority.ALWAYS);
        hint.setMaxWidth(Double.MAX_VALUE);
        HBox hintRow = new HBox(12, hint, version);
        hintRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, row, hintRow);
        box.setPadding(new Insets(8, 12, 10, 12));
        return box;
    }

    /** Buttons, labels and spinners never shrink below their text; only fields and the combo grow/shrink. */
    private static void keepPreferredWidth(HBox row) {
        for (javafx.scene.Node node : row.getChildren()) {
            if (node instanceof Button || node instanceof Label) {
                ((Region) node).setMinWidth(Region.USE_PREF_SIZE);
            }
        }
    }

    /** Devices with maps, plus connected iGPSPORT devices this app cannot update. */
    private record Detection(List<DetectedSource> found, List<UnsupportedDevice> unsupported) {
    }

    private void detectDevices() {
        run("Looking for IGPSPORT devices…", () -> new Detection(controller.detect(), controller.detectUnsupported()),
                detection -> {
            List<DetectedSource> found = detection.found();
            sources.getItems().setAll(found);
            if (found.isEmpty()) {
                Path last = controller.settings().lastSourceFolder();
                status.setText(detection.unsupported().isEmpty()
                        ? "No device with IGPSPORT maps found. Plug it in and rescan, or choose a folder."
                        : "Connected device not supported: " + detection.unsupported().get(0).message());
                if (last != null && Files.isDirectory(last)) {
                    loadFolder(last);
                }
            } else {
                sources.getSelectionModel().select(0);
            }
            if (!detection.unsupported().isEmpty()) {
                List<String> messages = detection.unsupported().stream().map(UnsupportedDevice::message).toList();
                Alert alert = new Alert(Alert.AlertType.WARNING, String.join("\n\n", messages));
                alert.initOwner(stage);
                alert.setHeaderText(messages.size() == 1 ? "Connected device not supported"
                        : messages.size() + " connected devices not supported");
                alert.setResizable(true);
                alert.show();
            }
        });
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Folder with original IGPSPORT maps");
        Path last = controller.settings().lastSourceFolder();
        if (last != null && Files.isDirectory(last)) {
            chooser.setInitialDirectory(last.toFile());
        }
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            controller.updateSettings(controller.settings().withLastSourceFolder(dir.toPath()));
            loadFolder(dir.toPath());
        }
    }

    private void loadFolder(Path folder) {
        run("Scanning " + folder + "…", () -> controller.scanFolder(folder), maps -> {
            DetectedSource source = new DetectedSource(DeviceScanner.label(folder, maps.size()), folder, maps);
            sources.getItems().removeIf(s -> s.root().equals(folder));
            sources.getItems().add(source);
            sources.getSelectionModel().select(source); // triggers matching
            if (maps.isEmpty()) {
                status.setText("No IGPSPORT map files (like BR01002303102B83FO00N00E.map) in " + folder);
            }
        });
    }

    private void matchMaps(List<DetectedMap> maps) {
        int request = ++matchRequest;
        table.setJobs(List.of());
        if (maps.isEmpty()) {
            return;
        }
        Task<List<MapJob>> task = new Task<>() {
            @Override
            protected List<MapJob> call() throws Exception {
                return controller.match(maps, p -> updateProgress(p, 1.0));
            }
        };
        busy.progressProperty().bind(task.progressProperty());
        runTask("Matching " + maps.size() + " maps to OpenStreetMap regions (first run downloads indexes)…", task, jobs -> {
            if (request != matchRequest) {
                return; // a newer match was started meanwhile; its result wins
            }
            busy.progressProperty().unbind();
            table.setJobs(jobs);
            long unmatched = jobs.stream().filter(j -> !j.runnable()).count();
            status.setText(jobs.size() + " maps matched" + (unmatched > 0 ? ", " + unmatched + " unmatched" : "")
                    + ". Review and press Generate.");
        });
    }

    /** Opens the official catalog; downloaded maps are matched and appended to the review list. */
    private void openAddMaps() {
        List<DetectedMap> inList = table.allJobs().stream().map(MapJob::map).toList();
        Optional<Product> preselected = controller.preselectProduct(sources.getValue());
        new AddMapsDialog(stage, controller, preselected, inList).showAndWait().ifPresent(outcome -> {
            controller.updateSettings(controller.settings().withLastProduct(outcome.product().name()));
            List<DetectedMap> added = AppController.toDetected(outcome.maps(), table.allJobs().stream().map(MapJob::map).toList());
            if (!outcome.errors().isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING, String.join("\n", outcome.errors()));
                alert.initOwner(stage);
                alert.setHeaderText(outcome.errors().size() + " of the selected entries could not be added");
                alert.show();
            }
            if (added.isEmpty()) {
                status.setText(outcome.maps().isEmpty() ? "No maps were added."
                        : "The downloaded maps are already in the list.");
                return;
            }
            appendMaps(added);
        });
    }

    private void appendMaps(List<DetectedMap> maps) {
        int request = matchRequest; // a source change meanwhile replaces the list; drop this result then
        Task<List<MapJob>> task = new Task<>() {
            @Override
            protected List<MapJob> call() throws Exception {
                return controller.match(maps, p -> updateProgress(p, 1.0));
            }
        };
        busy.progressProperty().bind(task.progressProperty());
        runTask("Matching " + maps.size() + " official maps to OpenStreetMap regions…", task, jobs -> {
            busy.progressProperty().unbind();
            if (request != matchRequest) {
                return;
            }
            List<MapJob> fresh = AppController.newJobs(table.allJobs(), jobs);
            table.addJobs(fresh);
            long unmatched = fresh.stream().filter(j -> !j.runnable()).count();
            status.setText("Added " + fresh.size() + (fresh.size() == 1 ? " official map" : " official maps")
                    + (unmatched > 0 ? ", " + unmatched + " unmatched" : "") + ". Review and press Generate.");
        });
    }

    private static ComboBox<Integer> autoCombo(int min, int max, String unit) {
        ComboBox<Integer> box = new ComboBox<>();
        box.getItems().add(Settings.AUTO);
        for (int i = min; i <= max; i++) {
            box.getItems().add(i);
        }
        box.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null || value == Settings.AUTO ? "Auto" : value + unit;
            }

            @Override
            public Integer fromString(String text) {
                return null;
            }
        });
        box.setVisibleRowCount(12);
        box.getSelectionModel().select(0);
        return box;
    }

    /** Selects {@code value}, adding it when it is outside the list (e.g. 96 GB typed in the settings file). */
    private static void selectAutoValue(ComboBox<Integer> box, int value) {
        if (!box.getItems().contains(value)) {
            box.getItems().add(value);
        }
        box.getSelectionModel().select(Integer.valueOf(value));
    }

    private static int autoValue(ComboBox<Integer> box) {
        return box.getValue() == null ? Settings.AUTO : box.getValue();
    }

    /** Natural Earth base map for the coverage tab, bundled with the app; parsed in the background. */
    private void loadBaseMap() {
        Task<BaseMap> task = new Task<>() {
            @Override
            protected BaseMap call() {
                return BaseMap.bundled();
            }
        };
        task.setOnSucceeded(e -> coverage.setBaseMap(task.getValue()));
        Thread thread = new Thread(task, "base-map");
        thread.setDaemon(true);
        thread.start();
    }

    private void startGeneration() {
        List<MapJob> selected = table.selectedJobs();
        if (selected.isEmpty()) {
            return;
        }
        Path output;
        try {
            output = PathText.parse(outputDir.getText(), PathText.home()).toAbsolutePath();
        } catch (java.nio.file.InvalidPathException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Invalid output folder: " + e.getMessage());
            alert.initOwner(stage);
            alert.show();
            return;
        }
        Settings settings = new Settings(output, autoValue(threads), autoValue(heap),
                controller.settings().cacheMaxAge(), controller.settings().lastSourceFolder(),
                hdFallback.isSelected(), controller.settings().lastProduct(), controller.settings().skippedVersion());
        controller.updateSettings(settings);
        cancelToken = new CancelToken();
        CancelToken token = cancelToken;
        runView.reset(selected.size());
        reviewPane.setVisible(false);
        runView.setVisible(true);

        Task<List<JobResult>> task = new Task<>() {
            @Override
            protected List<JobResult> call() throws Exception {
                controller.confirm(selected);
                return controller.generate(selected, runView, token);
            }
        };
        task.setOnSucceeded(e -> {
            runView.finished(task.getValue());
            List<String> warnings = JobResult.warnings(task.getValue());
            if (!warnings.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING, String.join("\n", warnings));
                alert.initOwner(stage);
                alert.setHeaderText("Memory mode failed for " + warnings.size() + (warnings.size() == 1 ? " map" : " maps"));
                alert.setResizable(true);
                alert.show();
            }
        });
        task.setOnFailed(e -> runView.failed(task.getException()));
        Thread thread = new Thread(task, "generation");
        thread.setDaemon(true);
        thread.start();
    }

    private void showReview() {
        runView.setVisible(false);
        reviewPane.setVisible(true);
    }

    private void updateGenerateButton() {
        int n = table.selectedJobs().size();
        generate.setText(n == 0 ? "Generate" : "Generate " + n + (n == 1 ? " map ▶" : " maps ▶"));
        generate.setDisable(n == 0);
    }

    @FunctionalInterface
    private interface Work<T> {
        T call() throws Exception;
    }

    private <T> void run(String message, Work<T> work, java.util.function.Consumer<T> onDone) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        busy.progressProperty().unbind();
        busy.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        runTask(message, task, onDone);
    }

    private <T> void runTask(String message, Task<T> task, java.util.function.Consumer<T> onDone) {
        status.setText(message);
        busy.setVisible(true);
        task.setOnSucceeded(e -> {
            busy.setVisible(false);
            onDone.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            busy.setVisible(false);
            busy.progressProperty().unbind();
            Throwable error = task.getException();
            status.setText("Error: " + error.getMessage());
            Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(error.getMessage()));
            alert.initOwner(stage);
            alert.setHeaderText(message.replace("…", "") + " failed");
            alert.show();
        });
        Thread thread = new Thread(task, "worker");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        if (cancelToken != null) {
            cancelToken.cancel();
        }
        controller.shutdown();
        Platform.exit();
    }

}
