package io.github.fernandoglatz.igpsmap.pipeline;

import io.github.fernandoglatz.igpsmap.domain.GeoCode;
import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import io.github.fernandoglatz.igpsmap.io.MapHeaderReader;
import io.github.fernandoglatz.igpsmap.io.PbfDateReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;

/** Moves a generated map into the output folder under its IGPSPORT name. */
public final class MapRenamer {

    private MapRenamer() {
    }

    public static Path rename(Path generated, MapFileName original, LocalDate date, Path outputDir) throws IOException {
        String geoCode = GeoCode.encode(MapHeaderReader.readBoundingBox(generated));
        MapFileName name = new MapFileName(original.countryCode(), original.regionCode(), PbfDateReader.format(date), geoCode);
        Files.createDirectories(outputDir);
        Path target = outputDir.resolve(name.toFileName());
        Files.move(generated, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
