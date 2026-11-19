package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

/** World map with one rectangle per device map, coloured by match status. */
final class CoverageView extends BorderPane {

    private static final Color WATER = Color.web("#cfe0ee");
    private static final Color LAND = Color.web("#f4f1ea");
    private static final Color BORDER = Color.web("#b5ad9f");
    private static final Color LABEL = Color.web("#6b6458");
    private static final javafx.scene.text.Font LABEL_FONT = javafx.scene.text.Font.font(11);
    private static final double MIN_RECT_PX = 3;

    private final Pane canvasHolder = new Pane();
    private final Canvas canvas = new Canvas();
    private final Label summary = new Label("No maps yet.");
    private final Label hover = new Label();
    private final Viewport viewport = new Viewport();
    private CoverageModel model = CoverageModel.of(List.of(), List.of());
    private BaseMap baseMap;
    private Consumer<MapJob> onOpen = job -> { };
    private boolean fitted;
    private double dragX;
    private double dragY;
    private boolean dragged;

    CoverageView() {
        Button fit = new Button("Fit");
        fit.setOnAction(e -> fitToMaps());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(12, summary, spacer, legend(CoverageModel.Status.OK), legend(CoverageModel.Status.PARTIAL),
                legend(CoverageModel.Status.BOUNDING_BOX), legend(CoverageModel.Status.UNMATCHED), fit);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8, 12, 8, 12));
        setTop(top);

        hover.getStyleClass().add("map-hover");
        hover.setVisible(false);
        hover.setMouseTransparent(true);
        canvasHolder.getChildren().addAll(canvas, hover);
        canvasHolder.setMinSize(0, 0);
        canvas.widthProperty().bind(canvasHolder.widthProperty());
        canvas.heightProperty().bind(canvasHolder.heightProperty());
        canvas.widthProperty().addListener((obs, a, b) -> onResize());
        canvas.heightProperty().addListener((obs, a, b) -> onResize());
        setCenter(canvasHolder);

        Label help = new Label("Scroll to zoom · drag to move · click a map to open it in Review");
        help.getStyleClass().add("hint");
        help.setPadding(new Insets(4, 12, 6, 12));
        setBottom(help);

        canvas.setOnScroll(e -> {
            viewport.zoomAt(e.getX(), e.getY(), e.getDeltaY() > 0 ? 1.25 : 0.8);
            redraw();
        });
        canvas.setOnMousePressed(e -> {
            dragX = e.getX();
            dragY = e.getY();
            dragged = false;
        });
        canvas.setOnMouseDragged(e -> {
            viewport.pan(e.getX() - dragX, e.getY() - dragY);
            dragged |= Math.abs(e.getX() - dragX) + Math.abs(e.getY() - dragY) > 2;
            dragX = e.getX();
            dragY = e.getY();
            hover.setVisible(false);
            redraw();
        });
        canvas.setOnMouseClicked(e -> {
            if (!dragged && e.getButton() == MouseButton.PRIMARY) {
                itemAt(e.getX(), e.getY()).ifPresent(item -> onOpen.accept(item.job()));
            }
        });
        canvas.setOnMouseMoved(e -> showHover(e.getX(), e.getY()));
        canvas.setOnMouseExited(e -> hover.setVisible(false));
    }

    void setOnOpen(Consumer<MapJob> onOpen) {
        this.onOpen = onOpen;
    }

    void setModel(CoverageModel newModel) {
        model = newModel;
        summary.setText(model.items().isEmpty() ? "No maps yet." : model.summary());
        if (!fitted && model.extent().isPresent() && canvas.getWidth() > 0) {
            fitToMaps();
        } else {
            redraw();
        }
    }

    void setBaseMap(BaseMap map) {
        baseMap = map;
        redraw();
    }

    void fitToMaps() {
        BoundingBox box = model.extent().orElse(new BoundingBox(-60, -170, 75, 170));
        viewport.fit(box, canvas.getWidth(), canvas.getHeight());
        fitted = model.extent().isPresent();
        redraw();
    }

    private void onResize() {
        if (!fitted && model.extent().isPresent()) {
            fitToMaps();
        } else {
            redraw();
        }
    }

    private java.util.Optional<CoverageModel.Item> itemAt(double x, double y) {
        return model.itemAt(viewport.lonAt(x), viewport.latAt(y));
    }

    private void showHover(double x, double y) {
        java.util.Optional<CoverageModel.Item> item = itemAt(x, y);
        if (item.isEmpty()) {
            hover.setVisible(false);
            return;
        }
        MapJob job = item.get().job();
        hover.setText(job.map().path().getFileName() + "  ·  " + ReviewTable.mapDate(job) + "\n"
                + (job.poly() == null ? "no polygon" : job.poly().label())
                + (job.pbfLabel() == null ? "" : "  ·  " + job.pbfLabel()) + "\n"
                + item.get().status().label + (item.get().selected() ? "" : " (not selected)"));
        hover.autosize();
        double hx = Math.min(x + 14, canvas.getWidth() - hover.getWidth() - 4);
        double hy = Math.min(y + 14, canvas.getHeight() - hover.getHeight() - 4);
        hover.relocate(Math.max(4, hx), Math.max(4, hy));
        hover.setVisible(true);
    }

    private void redraw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(WATER);
        g.fillRect(0, 0, w, h);
        if (baseMap != null) {
            drawBaseMap(g, w, h);
        }
        // Unselected first, so selected maps are drawn on top.
        for (boolean selectedPass : new boolean[] {false, true}) {
            for (CoverageModel.Item item : model.items()) {
                if (item.selected() == selectedPass) {
                    drawItem(g, item, w, h);
                }
            }
        }
        if (baseMap != null) {
            drawLabels(g, w, h);
        }
    }

    private Envelope visibleArea(double w, double h) {
        return new Envelope(viewport.lonAt(0), viewport.lonAt(w), viewport.latAt(h), viewport.latAt(0));
    }

    private void drawBaseMap(GraphicsContext g, double w, double h) {
        Envelope visible = visibleArea(w, h);
        double scale = viewport.scale();
        g.setFill(LAND);
        g.setStroke(BORDER);
        g.setLineWidth(0.7);
        for (BaseMap.Country country : baseMap.countries(scale)) {
            fillGeometry(g, country.geometry(), visible);
        }
        g.setFill(WATER);
        g.setStroke(WATER.darker());
        g.setLineWidth(0.4);
        for (Geometry lake : baseMap.lakes(scale)) {
            fillGeometry(g, lake, visible);
        }
    }

    /** Fills and strokes every polygon (holes included, even-odd) that touches the visible area. */
    private void fillGeometry(GraphicsContext g, Geometry geometry, Envelope visible) {
        if (!geometry.getEnvelopeInternal().intersects(visible)) {
            return;
        }
        g.setFillRule(javafx.scene.shape.FillRule.EVEN_ODD);
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            if (!(geometry.getGeometryN(i) instanceof Polygon polygon)
                    || !polygon.getEnvelopeInternal().intersects(visible)) {
                continue;
            }
            g.beginPath();
            addRing(g, polygon.getExteriorRing().getCoordinates());
            for (int r = 0; r < polygon.getNumInteriorRing(); r++) {
                addRing(g, polygon.getInteriorRingN(r).getCoordinates());
            }
            g.fill();
            g.stroke();
        }
    }

    private void addRing(GraphicsContext g, Coordinate[] ring) {
        g.moveTo(viewport.screenX(ring[0].x), viewport.screenY(ring[0].y));
        for (int k = 1; k < ring.length; k++) {
            g.lineTo(viewport.screenX(ring[k].x), viewport.screenY(ring[k].y));
        }
        g.closePath();
    }

    /** Country names (Natural Earth label points), skipping any that would overlap one already drawn. */
    private void drawLabels(GraphicsContext g, double w, double h) {
        double scale = viewport.scale();
        g.setFont(LABEL_FONT);
        g.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        g.setTextBaseline(javafx.geometry.VPos.CENTER);
        g.setLineWidth(3);
        List<javafx.geometry.Rectangle2D> placed = new java.util.ArrayList<>();
        for (BaseMap.Country country : baseMap.countries(scale)) {
            if (!BaseMap.labelVisible(country, scale)) {
                continue;
            }
            double x = viewport.screenX(country.labelLon()), y = viewport.screenY(country.labelLat());
            double tw = country.name().length() * 6.2, th = 14;
            javafx.geometry.Rectangle2D box = new javafx.geometry.Rectangle2D(x - tw / 2, y - th / 2, tw, th);
            if (x < 0 || y < 0 || x > w || y > h || placed.stream().anyMatch(box::intersects)) {
                continue;
            }
            placed.add(box);
            g.setStroke(Color.rgb(255, 255, 255, 0.85));
            g.strokeText(country.name(), x, y);
            g.setFill(LABEL);
            g.fillText(country.name(), x, y);
        }
    }

    private void drawItem(GraphicsContext g, CoverageModel.Item item, double w, double h) {
        double x0 = viewport.screenX(item.box().minLon()), x1 = viewport.screenX(item.box().maxLon());
        double y0 = viewport.screenY(item.box().maxLat()), y1 = viewport.screenY(item.box().minLat());
        if (x1 < 0 || y1 < 0 || x0 > w || y0 > h) {
            return;
        }
        double rw = Math.max(MIN_RECT_PX, x1 - x0), rh = Math.max(MIN_RECT_PX, y1 - y0);
        Color color = color(item.status());
        if (item.selected()) {
            g.setFill(color.deriveColor(0, 1, 1, 0.22));
            g.fillRect(x0, y0, rw, rh);
        }
        g.setStroke(color);
        g.setLineWidth(item.selected() ? 1.2 : 1.0);
        g.setLineDashes(item.selected() ? null : new double[] {4, 3});
        g.strokeRect(x0, y0, rw, rh);
        g.setLineDashes((double[]) null);
    }

    static Color color(CoverageModel.Status status) {
        return switch (status) {
            case OK -> Color.web("#1e7b34");
            case PARTIAL -> Color.web("#c08400");
            case BOUNDING_BOX -> Color.web("#d4561a");
            case UNMATCHED -> Color.web("#b42318");
        };
    }

    private static HBox legend(CoverageModel.Status status) {
        Rectangle swatch = new Rectangle(12, 12, color(status).deriveColor(0, 1, 1, 0.35));
        swatch.setStroke(color(status));
        Label label = new Label(status.label.substring(0, 1).toUpperCase(Locale.ROOT) + status.label.substring(1));
        HBox box = new HBox(5, swatch, label);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
}
