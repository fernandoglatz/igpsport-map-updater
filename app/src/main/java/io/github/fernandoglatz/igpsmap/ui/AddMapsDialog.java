package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.catalog.CatalogNode;
import io.github.fernandoglatz.igpsmap.catalog.OfficialMapDownloader;
import io.github.fernandoglatz.igpsmap.catalog.Product;
import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.pipeline.CancelToken;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * Lets the user pick maps from the official iGPSPORT catalog of a product and downloads their original
 * files. The result holds the downloaded maps and the entries that failed.
 */
final class AddMapsDialog extends Dialog<AddMapsDialog.Outcome> {

    /** Downloaded original map files, plus one message per entry that could not be used. */
    record Outcome(Product product, List<Path> maps, List<String> errors) {
    }

    private static final ButtonType ADD = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);

    private final AppController controller;
    private final Collection<DetectedMap> inList;
    private final ComboBox<Product> product = new ComboBox<>();
    private final TextField filter = new TextField();
    private final TreeView<CatalogNode> tree = new TreeView<>();
    private final Label summary = new Label();
    private final Label progressText = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    /** Checked downloadable entries by URL, kept across filter changes, in check order. */
    private final Map<String, CatalogNode> checked = new LinkedHashMap<>();
    private List<CatalogNode> catalog = List.of();
    private int loadRequest;
    private Task<Outcome> download;
    private CancelToken cancel;

    AddMapsDialog(Window owner, AppController controller, Optional<Product> preselected, Collection<DetectedMap> inList) {
        this.controller = controller;
        this.inList = List.copyOf(inList);
        initOwner(owner);
        setTitle("Add official maps");
        setHeaderText("Download original maps from iGPSPORT to regenerate regions that are not on the device.");
        setResizable(true);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ADD);
        getDialogPane().setPrefSize(640, 600);

        product.getItems().setAll(Product.values());
        product.setPromptText("Choose your device");
        product.setOnAction(e -> loadCatalog());
        filter.setPromptText("Filter, e.g. brazil");
        filter.textProperty().addListener((obs, was, now) -> rebuildTree());
        HBox.setHgrow(filter, Priority.ALWAYS);
        HBox top = new HBox(8, new Label("Product:"), product, new Label("Filter:"), filter);
        top.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.setCellFactory(view -> new CatalogCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progressText.getStyleClass().add("hint");
        Label note = new Label("Sizes are the official package sizes; only the main map file is downloaded. "
                + "Maps already in the list are greyed out.");
        note.getStyleClass().add("hint");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE); // wrap instead of cutting the text off
        VBox content = new VBox(8, top, tree, summary, progress, progressText, note);
        content.setPadding(new Insets(8, 12, 4, 12));
        getDialogPane().setContent(content);

        Node addButton = getDialogPane().lookupButton(ADD);
        addButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume(); // stay open while downloading
            startDownload();
        });
        Node cancelButton = getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelButton.addEventFilter(ActionEvent.ACTION, e -> {
            if (download != null && download.isRunning()) {
                e.consume();
                cancel.cancel();
                progressText.setText("Cancelling…");
            }
        });
        setOnCloseRequest(e -> {
            if (download != null && download.isRunning()) {
                cancel.cancel(); // window closed: stop the transfer, the result is discarded
            }
        });
        setResultConverter(button -> null);
        updateSummary();

        preselected.ifPresent(p -> {
            product.getSelectionModel().select(p);
            Platform.runLater(this::loadCatalog);
        });
        if (preselected.isEmpty()) {
            tree.setRoot(null);
            summary.setText("Choose your device to see its official maps.");
        }
    }

    private void loadCatalog() {
        Product selected = product.getValue();
        if (selected == null) {
            return;
        }
        int request = ++loadRequest;
        checked.clear();
        catalog = List.of();
        tree.setRoot(null);
        summary.setText("Loading the " + selected + " catalog…");
        Task<List<CatalogNode>> task = new Task<>() {
            @Override
            protected List<CatalogNode> call() throws Exception {
                return controller.catalog(selected);
            }
        };
        task.setOnSucceeded(e -> {
            if (request == loadRequest) {
                catalog = task.getValue();
                rebuildTree();
            }
        });
        task.setOnFailed(e -> {
            if (request == loadRequest) {
                summary.setText("Could not load the catalog: " + task.getException().getMessage());
            }
        });
        Thread thread = new Thread(task, "catalog");
        thread.setDaemon(true);
        thread.start();
    }

    private void rebuildTree() {
        CheckBoxTreeItem<CatalogNode> root = new CheckBoxTreeItem<>();
        for (CatalogNode node : catalog) {
            TreeItem<CatalogNode> item = item(node);
            if (item != null) {
                root.getChildren().add(item);
            }
        }
        root.setExpanded(true);
        boolean filtering = !filter.getText().isBlank();
        if (filtering) {
            expandAll(root);
        }
        tree.setRoot(root);
        updateSummary();
    }

    /** Tree item for {@code node}, or null when neither it nor anything below matches the filter. */
    private TreeItem<CatalogNode> item(CatalogNode node) {
        List<TreeItem<CatalogNode>> children = new ArrayList<>();
        for (CatalogNode child : node.children()) {
            TreeItem<CatalogNode> item = item(child);
            if (item != null) {
                children.add(item);
            }
        }
        boolean self = node.downloadable() && CatalogSelection.matches(node, filter.getText());
        if (!self && children.isEmpty()) {
            return null;
        }
        if (node.downloadable() && CatalogSelection.inList(node, inList)) {
            TreeItem<CatalogNode> plain = new TreeItem<>(node); // no checkbox: already in the list
            plain.getChildren().setAll(children);
            return plain;
        }
        CheckBoxTreeItem<CatalogNode> item = new CheckBoxTreeItem<>(node);
        item.getChildren().setAll(children);
        if (node.downloadable()) {
            item.setSelected(checked.containsKey(node.url().toString()));
            item.selectedProperty().addListener((obs, was, now) -> {
                if (now) {
                    checked.put(node.url().toString(), node);
                } else {
                    checked.remove(node.url().toString());
                }
                updateSummary();
            });
        }
        return item;
    }

    private static void expandAll(TreeItem<CatalogNode> item) {
        item.setExpanded(true);
        item.getChildren().forEach(AddMapsDialog::expandAll);
    }

    private void updateSummary() {
        long bytes = checked.values().stream().mapToLong(CatalogNode::size).sum();
        int n = checked.size();
        if (product.getValue() != null && !catalog.isEmpty()) {
            summary.setText(n == 0 ? "Check the regions to add."
                    : n + (n == 1 ? " map" : " maps") + " selected · " + CatalogSelection.size(bytes) + " in packages");
        }
        getDialogPane().lookupButton(ADD).setDisable(n == 0 || (download != null && download.isRunning()));
    }

    private void startDownload() {
        Product selected = product.getValue();
        List<CatalogNode> nodes = List.copyOf(checked.values());
        if (selected == null || nodes.isEmpty()) {
            return;
        }
        CancelToken token = new CancelToken();
        cancel = token;
        download = new Task<>() {
            @Override
            protected Outcome call() {
                token.bind(Thread.currentThread());
                try {
                    List<Path> maps = new ArrayList<>();
                    List<String> errors = new ArrayList<>();
                    for (int i = 0; i < nodes.size(); i++) {
                        CatalogNode node = nodes.get(i);
                        String label = node.name() + " (" + (i + 1) + "/" + nodes.size() + ")";
                        updateMessage("Downloading " + label + "…");
                        updateProgress(-1, 1);
                        OfficialMapDownloader.Result result = controller.downloadOfficial(node, selected, (done, total) -> {
                            if (token.isCancelled()) {
                                throw new CancellationException();
                            }
                            if (total > 0) {
                                updateProgress(done, total);
                                updateMessage("Downloading " + label + " · " + CatalogSelection.size(done)
                                        + " of " + CatalogSelection.size(total));
                            }
                        });
                        if (token.isCancelled()) {
                            throw new CancellationException();
                        }
                        maps.addAll(result.maps());
                        if (result.error() != null) {
                            errors.add(result.error());
                        }
                    }
                    return new Outcome(selected, maps, errors);
                } finally {
                    token.unbind();
                    Thread.interrupted(); // a cancel may have interrupted this thread
                }
            }
        };
        product.setDisable(true);
        filter.setDisable(true);
        tree.setDisable(true);
        progress.setVisible(true);
        progress.progressProperty().bind(download.progressProperty());
        progressText.textProperty().bind(download.messageProperty());
        updateSummary();
        download.setOnSucceeded(e -> {
            setResult(download.getValue());
            close();
        });
        download.setOnFailed(e -> {
            progressText.textProperty().unbind();
            progress.progressProperty().unbind();
            progress.setVisible(false);
            product.setDisable(false);
            filter.setDisable(false);
            tree.setDisable(false);
            Throwable error = download.getException();
            progressText.setText(error instanceof CancellationException || token.isCancelled()
                    ? "Cancelled." : "Download failed: " + error.getMessage());
            updateSummary();
        });
        Thread thread = new Thread(download, "official-maps");
        thread.setDaemon(true);
        thread.start();
    }

    /** Checkbox cell; entries already in the review list show no checkbox and are greyed out. */
    private final class CatalogCell extends CheckBoxTreeCell<CatalogNode> {

        CatalogCell() {
            super(item -> item instanceof CheckBoxTreeItem<CatalogNode> box ? box.selectedProperty() : null,
                    new StringConverter<>() {
                        @Override
                        public String toString(TreeItem<CatalogNode> item) {
                            return item == null || item.getValue() == null ? "" : label(item.getValue(), AddMapsDialog.this.inList);
                        }

                        @Override
                        public TreeItem<CatalogNode> fromString(String string) {
                            return null;
                        }
                    });
        }

        @Override
        public void updateItem(CatalogNode node, boolean empty) {
            super.updateItem(node, empty);
            boolean inListRow = !empty && node != null && !(getTreeItem() instanceof CheckBoxTreeItem);
            setDisable(inListRow);
            setOpacity(inListRow ? 0.5 : 1);
            if (inListRow) {
                setGraphic(null);
            }
        }

    }

    private static String label(CatalogNode node, Collection<DetectedMap> inList) {
        String size = CatalogSelection.size(node.size());
        String text = node.name() + (node.downloadable() && !size.isEmpty() ? "   " + size : "");
        return node.downloadable() && CatalogSelection.inList(node, inList) ? text + "   (in list)" : text;
    }
}
