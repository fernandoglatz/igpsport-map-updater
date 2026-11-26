package io.github.fernandoglatz.igpsmap.viewer;

import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.domain.TileBox;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds the map generated from an original in the output folder. Generated maps keep the original's
 * country and region code but get a new date and a GEOCODE from their own bounding box, so the match
 * is: same country and region code, an overlapping GEOCODE area, newest date.
 */
public final class GeneratedMaps {

    private GeneratedMaps() {
    }

    public static Optional<Path> find(DetectedMap original, Path outputDir) throws IOException {
        if (outputDir == null || !Files.isDirectory(outputDir)) {
            return Optional.empty();
        }
        MapFileName name = original.name();
        TileBox area = GeoCode.decode(name.geoCode());
        Path self = original.path().toAbsolutePath().normalize();
        try (Stream<Path> files = Files.list(outputDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toAbsolutePath().normalize().equals(self))
                    .flatMap(p -> MapFileName.parse(p.getFileName().toString()).map(n -> new Candidate(p, n)).stream())
                    .filter(c -> c.name().countryCode().equals(name.countryCode())
                            && c.name().regionCode().equals(name.regionCode()))
                    .filter(c -> GeoCode.decode(c.name().geoCode()).iou(area) > 0)
                    .max(Comparator.comparing((Candidate c) -> c.name().date()))
                    .map(Candidate::path);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private record Candidate(Path path, MapFileName name) {
    }
}
