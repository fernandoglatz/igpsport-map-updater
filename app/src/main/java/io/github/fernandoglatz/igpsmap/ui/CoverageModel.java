package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.matching.Confidence;
import io.github.fernandoglatz.igpsmap.matching.MapJob;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What the coverage map shows: one rectangle per device map, with its match status. */
final class CoverageModel {

    enum Status {
        OK("matched"), PARTIAL("partial"), BOUNDING_BOX("bounding box"), UNMATCHED("unmatched");

        final String label;

        Status(String label) {
            this.label = label;
        }

        static Status of(Confidence confidence) {
            return switch (confidence) {
                case CSV, MANUAL, HIGH -> OK;
                case MEDIUM, LOW -> PARTIAL;
                case FALLBACK -> BOUNDING_BOX;
                case UNMATCHED -> UNMATCHED;
            };
        }
    }

    /** The area a device map covers: its GEOCODE rectangle. */
    record Item(MapJob job, BoundingBox box, Status status, boolean selected) {

        double area() {
            return (box.maxLon() - box.minLon()) * (box.maxLat() - box.minLat());
        }

        boolean contains(double lon, double lat) {
            return lon >= box.minLon() && lon <= box.maxLon() && lat >= box.minLat() && lat <= box.maxLat();
        }
    }

    private final List<Item> items;

    private CoverageModel(List<Item> items) {
        this.items = List.copyOf(items);
    }

    static CoverageModel of(List<MapJob> jobs, Collection<MapJob> selected) {
        List<Item> items = new ArrayList<>();
        for (MapJob job : jobs) {
            BoundingBox box = GeoCode.decode(job.map().name().geoCode()).toBoundingBox();
            items.add(new Item(job, box, Status.of(job.confidence()), selected.contains(job)));
        }
        return new CoverageModel(items);
    }

    List<Item> items() {
        return items;
    }

    /** E.g. "265 maps · 39 countries · 258 matched · 4 partial · 3 bounding box". */
    String summary() {
        long countries = items.stream().map(i -> i.job().map().name().countryCode()).distinct().count();
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        items.forEach(i -> counts.merge(i.status(), 1L, Long::sum));
        StringBuilder text = new StringBuilder()
                .append(items.size()).append(items.size() == 1 ? " map" : " maps")
                .append(" · ").append(countries).append(countries == 1 ? " country" : " countries");
        counts.forEach((status, n) -> text.append(" · ").append(n).append(' ').append(status.label));
        return text.toString();
    }

    /** The smallest map rectangle containing the point, so nested regions stay reachable. */
    Optional<Item> itemAt(double lon, double lat) {
        return items.stream().filter(i -> i.contains(lon, lat)).min(Comparator.comparingDouble(Item::area));
    }

    Optional<BoundingBox> extent() {
        if (items.isEmpty()) {
            return Optional.empty();
        }
        double minLat = 90, minLon = 180, maxLat = -90, maxLon = -180;
        for (Item i : items) {
            minLat = Math.min(minLat, i.box().minLat());
            minLon = Math.min(minLon, i.box().minLon());
            maxLat = Math.max(maxLat, i.box().maxLat());
            maxLon = Math.max(maxLon, i.box().maxLon());
        }
        return Optional.of(new BoundingBox(minLat, minLon, maxLat, maxLon));
    }
}
