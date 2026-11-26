package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.viewer.GeneratedMaps;
import io.github.fernandoglatz.igpsmap.viewer.MapTileRenderer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The original map and the map generated from it, side by side. Both panes share one viewport, so
 * panning or zooming either moves both to the same place.
 */
final class MapViewerView extends BorderPane {

    static final String EMPTY = "Select a map in Review and press View map.";

    private final Viewport viewport = new Viewport(TileLayout.MAX_SCALE);
    private final MapPane original = new MapPane("map-viewer-original", viewport, this::viewChanged);
    private final MapPane generated = new MapPane("map-viewer-generated", viewport, this::viewChanged);
    private final Label heading = new Label("No map open.");
    private final Label zoom = new Label();
    private final Button fit = new Button("Fit");
    private BoundingBox fitBox;
    private boolean fitted;

    MapViewerView() {
        fit.setOnAction(e -> fit());
        fit.setDisable(true);
        heading.setMinWidth(0);
        zoom.setMinWidth(Region.USE_PREF_SIZE);
        fit.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(12, heading, spacer, zoom, fit);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8, 12, 8, 12));
        setTop(top);

        SplitPane split = new SplitPane(original, generated);
        split.setDividerPositions(0.5);
        setCenter(split);

        Label help = new Label("Scroll or double-click to zoom · drag to move · both maps move together. "
                + "Drawn with Mapsforge's default style, not the device's.");
        help.getStyleClass().add("hint");
        help.setPadding(new Insets(4, 12, 6, 12));
        setBottom(help);

        original.widthProperty().addListener((obs, a, b) -> fitOnFirstLayout());
        original.heightProperty().addListener((obs, a, b) -> fitOnFirstLayout());
        original.clear(EMPTY);
        generated.clear(null);
    }

    /** Shows {@code map} on the left and the newest map generated from it in {@code outputDir} on the right. */
    void show(DetectedMap map, Path outputDir) {
        heading.setText(map.path().getFileName() + "  ·  " + map.path().getParent());
        fitBox = null;
        fitted = false;
        fit.setDisable(true);
        original.load(map.path(), "Original" + dateSuffix(map.name()), box -> {
            fitBox = box;
            fit.setDisable(false);
            fitOnFirstLayout();
        });
        Optional<Path> newer;
        try {
            newer = GeneratedMaps.find(map, outputDir);
        } catch (IOException | RuntimeException e) {
            generated.clear("Could not look in the output folder " + outputDir + ":\n" + e.getMessage());
            return;
        }
        if (newer.isPresent()) {
            Path file = newer.get();
            generated.load(file, "Generated" + MapFileName.parse(file.getFileName().toString())
                    .map(MapViewerView::dateSuffix).orElse(""), box -> { });
        } else {
            generated.clear("No map generated from " + map.path().getFileName() + " in the output folder"
                    + (outputDir == null ? "." : ":\n" + outputDir) + "\n\nGenerate it, then press View map again.");
        }
    }

    /** Closes both map files. */
    void dispose() {
        original.dispose();
        generated.dispose();
    }

    /** " · 2023-03-10" from the file name's date, or "" when it is not a real date. */
    static String dateSuffix(MapFileName name) {
        return name.mapDate().map(d -> "  ·  " + d).orElse("");
    }

    private void fitOnFirstLayout() {
        if (!fitted && fitBox != null && original.getWidth() > 0 && original.getHeight() > 0) {
            fit();
        }
    }

    private void fit() {
        if (fitBox == null) {
            return;
        }
        viewport.fit(fitBox, original.getWidth(), original.getHeight());
        fitted = original.getWidth() > 0;
        viewChanged();
    }

    private void viewChanged() {
        original.redraw();
        generated.redraw();
        zoom.setText(fitBox == null ? "" : "Zoom " + Math.round(Math.log(viewport.scale() / MapTileRenderer.TILE_SIZE) / Math.log(2)));
    }
}
