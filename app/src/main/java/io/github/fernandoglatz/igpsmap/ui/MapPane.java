package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.viewer.MapTileRenderer;
import io.github.fernandoglatz.igpsmap.viewer.TileKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/**
 * One rendered .map file on a canvas. Tiles are rendered on a single background thread and kept in a
 * small LRU cache; while a tile renders, an enlarged piece of a cached lower-zoom tile stands in for it.
 * The {@link Viewport} is shared with the other pane, so both show the same place; pan and zoom here
 * call {@code onViewChanged}, which redraws every pane.
 */
final class MapPane extends StackPane {

    private static final int CACHE_TILES = 192;
    /** How many zoom levels up to look for a stand-in tile. */
    private static final int FALLBACK_LEVELS = 4;
    private static final Color OUTSIDE = Color.web("#e4e1da");
    private static final Color BOUNDS = Color.web("#6b6458");

    private final Viewport viewport;
    private final Runnable onViewChanged;
    private final Pane canvasHolder = new Pane();
    private final Canvas canvas = new Canvas();
    private final Label title = new Label();
    private final Label message = new Label();
    private final ExecutorService worker;
    /** Tiles on screen right now; the worker skips queued tiles that scrolled away. */
    private final Set<TileKey> wanted = ConcurrentHashMap.newKeySet();
    /** FX thread only. */
    private final Map<TileKey, Image> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TileKey, Image> eldest) {
            return size() > CACHE_TILES;
        }
    };
    private final Set<TileKey> pending = new HashSet<>();
    private final Set<TileKey> failed = new HashSet<>();
    private MapTileRenderer renderer;
    private BoundingBox bounds;
    /** Bumped on every load/clear; work for an older generation is dropped. Written on the FX thread. */
    private volatile int generation;
    private boolean redrawQueued;
    private double dragX;
    private double dragY;

    MapPane(String threadName, Viewport viewport, Runnable onViewChanged) {
        this.viewport = viewport;
        this.onViewChanged = onViewChanged;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, threadName);
            thread.setDaemon(true);
            return thread;
        });

        canvasHolder.getChildren().add(canvas);
        canvasHolder.setMinSize(0, 0);
        canvas.widthProperty().bind(canvasHolder.widthProperty());
        canvas.heightProperty().bind(canvasHolder.heightProperty());
        canvas.widthProperty().addListener((obs, a, b) -> redraw());
        canvas.heightProperty().addListener((obs, a, b) -> redraw());

        title.getStyleClass().add("map-hover");
        title.setMouseTransparent(true);
        title.setVisible(false);
        StackPane.setAlignment(title, Pos.TOP_LEFT);
        StackPane.setMargin(title, new javafx.geometry.Insets(8));
        message.setWrapText(true);
        message.setMouseTransparent(true);
        message.getStyleClass().add("map-message");
        getChildren().addAll(canvasHolder, title, message);
        setMinSize(0, 0);

        canvas.setOnScroll(e -> {
            if (e.getDeltaY() != 0) {
                viewport.zoomAt(e.getX(), e.getY(), e.getDeltaY() > 0 ? 1.25 : 0.8);
                onViewChanged.run();
            }
        });
        canvas.setOnMousePressed(e -> {
            dragX = e.getX();
            dragY = e.getY();
        });
        canvas.setOnMouseDragged(e -> {
            viewport.pan(e.getX() - dragX, e.getY() - dragY);
            dragX = e.getX();
            dragY = e.getY();
            onViewChanged.run();
        });
        canvas.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                viewport.zoomAt(e.getX(), e.getY(), 2.0);
                onViewChanged.run();
            }
        });
    }

    /**
     * Opens {@code file} in the background and shows it under {@code heading}; {@code onLoaded} gets the
     * map's bounding box once it is open.
     */
    void load(Path file, String heading, Consumer<BoundingBox> onLoaded) {
        int gen = reset();
        title.setText(heading + "\n" + file.getFileName());
        title.setVisible(true);
        showMessage("Opening " + file.getFileName() + "…");
        worker.execute(() -> {
            MapTileRenderer opened;
            try {
                opened = MapTileRenderer.open(file);
            } catch (IOException | RuntimeException | LinkageError e) {
                Platform.runLater(() -> {
                    if (gen == generation) {
                        showMessage("Could not open " + file.getFileName() + ":\n" + describe(e));
                    }
                });
                return;
            }
            Platform.runLater(() -> {
                if (gen != generation) { // superseded while opening
                    if (worker.isShutdown()) {
                        opened.close();
                    } else {
                        worker.execute(opened::close);
                    }
                    return;
                }
                renderer = opened;
                bounds = opened.boundingBox();
                showMessage(null);
                onLoaded.accept(bounds);
                redraw();
            });
        });
    }

    /** Closes the current map (if any) and shows {@code text} instead. */
    void clear(String text) {
        reset();
        title.setVisible(false);
        showMessage(text);
        redraw();
    }

    /** Stops rendering and closes the map file, e.g. so a device can be unmounted. */
    void dispose() {
        reset();
        worker.shutdown();
    }

    /** Drops the current map and everything rendered from it; returns the new generation. */
    private int reset() {
        int gen = generation + 1;
        generation = gen;
        MapTileRenderer old = renderer;
        renderer = null;
        bounds = null;
        cache.clear();
        pending.clear();
        failed.clear();
        wanted.clear();
        if (old != null && !worker.isShutdown()) {
            worker.execute(old::close); // after any tile it is rendering right now
        }
        return gen;
    }

    private void showMessage(String text) {
        message.setText(text == null ? "" : text);
        message.setVisible(text != null);
    }

    /** Coalesces redraws requested by finished tiles into one per pulse. */
    private void requestRedraw() {
        if (!redrawQueued) {
            redrawQueued = true;
            Platform.runLater(() -> {
                redrawQueued = false;
                redraw();
            });
        }
    }

    void redraw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(OUTSIDE);
        g.fillRect(0, 0, w, h);
        if (renderer == null || bounds == null) {
            wanted.clear();
            return;
        }
        TileLayout layout = TileLayout.of(viewport, w, h, bounds, TileLayout.MAX_TILE_ZOOM);
        wanted.clear();
        layout.tiles().forEach(t -> wanted.add(t.key()));
        g.setImageSmoothing(true);
        double cx = w / 2, cy = h / 2;
        List<TileLayout.Placed> byDistance = layout.tiles().stream()
                .sorted(Comparator.comparingDouble((TileLayout.Placed t) ->
                        Math.hypot(t.x() + t.size() / 2 - cx, t.y() + t.size() / 2 - cy)))
                .toList();
        for (TileLayout.Placed tile : byDistance) {
            // Snap to whole pixels so neighbouring tiles share an edge instead of leaving a hairline gap.
            double x0 = Math.floor(tile.x()), y0 = Math.floor(tile.y());
            double size = Math.floor(tile.x() + tile.size()) - x0;
            double sizeY = Math.floor(tile.y() + tile.size()) - y0;
            Image image = cache.get(tile.key());
            if (image != null) {
                g.drawImage(image, x0, y0, size, sizeY);
                continue;
            }
            drawStandIn(g, tile.key(), x0, y0, size, sizeY);
            requestTile(tile.key());
        }
        drawBounds(g);
    }

    /** Draws the part of the nearest cached ancestor tile that covers {@code key}, enlarged. */
    private void drawStandIn(GraphicsContext g, TileKey key, double x, double y, double w, double h) {
        TileKey ancestor = key;
        for (int level = 1; level <= FALLBACK_LEVELS && ancestor.zoom() > 0; level++) {
            ancestor = ancestor.parent();
            Image image = cache.get(ancestor);
            if (image != null) {
                int parts = 1 << level;
                double part = (double) MapTileRenderer.TILE_SIZE / parts;
                double sx = (key.x() - ((long) ancestor.x() << level)) * part;
                double sy = (key.y() - ((long) ancestor.y() << level)) * part;
                g.drawImage(image, sx, sy, part, part, x, y, w, h);
                return;
            }
        }
    }

    private void requestTile(TileKey key) {
        if (pending.contains(key) || failed.contains(key)) {
            return;
        }
        pending.add(key);
        MapTileRenderer current = renderer;
        int gen = generation;
        worker.execute(() -> {
            if (gen != generation || !wanted.contains(key)) {
                Platform.runLater(() -> {
                    if (gen == generation) {
                        pending.remove(key);
                        if (wanted.contains(key)) {
                            requestRedraw(); // scrolled back while it was skipped: queue it again
                        }
                    }
                });
                return;
            }
            try {
                int[] pixels = current.render(key);
                WritableImage image = new WritableImage(MapTileRenderer.TILE_SIZE, MapTileRenderer.TILE_SIZE);
                image.getPixelWriter().setPixels(0, 0, MapTileRenderer.TILE_SIZE, MapTileRenderer.TILE_SIZE,
                        PixelFormat.getIntArgbInstance(), pixels, 0, MapTileRenderer.TILE_SIZE);
                Platform.runLater(() -> {
                    if (gen == generation) {
                        pending.remove(key);
                        cache.put(key, image);
                        requestRedraw();
                    }
                });
            } catch (IOException | RuntimeException | LinkageError e) {
                Platform.runLater(() -> {
                    if (gen == generation) {
                        pending.remove(key);
                        failed.add(key);
                        showMessage("Some tiles could not be drawn: " + describe(e));
                    }
                });
            }
        });
    }

    /** The message, or the exception type when there is none (e.g. a missing class). */
    private static String describe(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }

    /** Dashed outline of the map's area, so it is clear where the map data ends. */
    private void drawBounds(GraphicsContext g) {
        double x0 = viewport.screenX(bounds.minLon()), x1 = viewport.screenX(bounds.maxLon());
        double y0 = viewport.screenY(bounds.maxLat()), y1 = viewport.screenY(bounds.minLat());
        g.setStroke(BOUNDS);
        g.setLineWidth(1);
        g.setLineDashes(6, 4);
        g.strokeRect(x0, y0, x1 - x0, y1 - y0);
        g.setLineDashes((double[]) null);
    }
}
