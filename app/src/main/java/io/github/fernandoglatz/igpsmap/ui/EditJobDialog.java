package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.domain.TileBox;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import io.github.fernandoglatz.igpsmap.matching.Matcher;
import io.github.fernandoglatz.igpsmap.matching.PolySource;
import io.github.fernandoglatz.igpsmap.sources.GeofabrikRegion;
import io.github.fernandoglatz.igpsmap.sources.PolyCandidate;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

/** Lets the user pick another polygon / PBF or type URLs for one job. */
final class EditJobDialog extends Dialog<MapJob> {

    private static final String CUSTOM = "Custom URL…";
    private static final String BBOX = "Use the file's bounding box";
    private static final String KEEP = "Keep current";

    EditJobDialog(Window owner, MapJob job) {
        initOwner(owner);
        setTitle("Edit match");
        setHeaderText(job.map().path().getFileName().toString());
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TileBox target = GeoCode.decode(job.map().name().geoCode());
        List<Object> polyOptions = new ArrayList<>();
        polyOptions.add(KEEP);
        polyOptions.addAll(job.polyAlternatives());
        polyOptions.add(BBOX);
        polyOptions.add(CUSTOM);
        ComboBox<Object> polyBox = new ComboBox<>();
        polyBox.getItems().setAll(polyOptions);
        polyBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Object o) {
                if (o instanceof PolyCandidate c) {
                    return c.name() + "  (" + Math.round(Matcher.score(target, c.tileBox()) * 100) + "%)";
                }
                return o == null ? "" : o.toString();
            }

            @Override
            public Object fromString(String s) {
                return s;
            }
        });
        polyBox.getSelectionModel().select(KEEP);
        TextField polyUrl = new TextField();
        polyUrl.setPromptText("https://download.openstreetmap.fr/polygons/…/region.poly");
        polyUrl.disableProperty().bind(polyBox.valueProperty().isNotEqualTo(CUSTOM));

        List<Object> pbfOptions = new ArrayList<>();
        pbfOptions.add(KEEP);
        pbfOptions.addAll(job.pbfAlternatives());
        pbfOptions.add(CUSTOM);
        ComboBox<Object> pbfBox = new ComboBox<>();
        pbfBox.getItems().setAll(pbfOptions);
        pbfBox.getSelectionModel().select(KEEP);
        TextField pbfUrl = new TextField();
        pbfUrl.setPromptText("https://download.geofabrik.de/…-latest.osm.pbf");
        pbfUrl.disableProperty().bind(pbfBox.valueProperty().isNotEqualTo(CUSTOM));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Current polygon:"), new Label(job.poly() == null ? "none" : job.poly().label()));
        grid.addRow(1, new Label("Polygon:"), polyBox);
        grid.addRow(2, new Label(""), polyUrl);
        grid.addRow(3, new Label("Current PBF:"), new Label(job.pbfLabel() == null ? "none" : job.pbfLabel()));
        grid.addRow(4, new Label("PBF extract:"), pbfBox);
        grid.addRow(5, new Label(""), pbfUrl);
        GridPane.setHgrow(polyUrl, Priority.ALWAYS);
        GridPane.setHgrow(pbfUrl, Priority.ALWAYS);
        polyUrl.setPrefColumnCount(48);
        getDialogPane().setContent(grid);

        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            MapJob result = job;
            Object poly = polyBox.getValue();
            if (poly instanceof PolyCandidate c) {
                result = result.withPoly(new PolySource.Remote(c.url(), c.name()));
            } else if (BBOX.equals(poly)) {
                result = result.withPoly(new PolySource.FromBoundingBox(target.toBoundingBox()));
            } else if (CUSTOM.equals(poly) && UrlInput.isHttpUrl(polyUrl.getText())) {
                URI uri = URI.create(polyUrl.getText().trim());
                result = result.withPoly(new PolySource.Remote(uri, lastSegment(uri)));
            }
            Object pbf = pbfBox.getValue();
            if (pbf instanceof GeofabrikRegion r) {
                result = result.withPbf(r.pbfUrl(), r.name());
            } else if (CUSTOM.equals(pbf) && UrlInput.isHttpUrl(pbfUrl.getText())) {
                URI uri = URI.create(pbfUrl.getText().trim());
                result = result.withPbf(uri, lastSegment(uri));
            }
            return result;
        });
    }

    private static String lastSegment(URI uri) {
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
