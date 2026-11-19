package io.github.fernandoglatz.igpsmap.device;

import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import java.nio.file.Path;

/** An original IGPSPORT map file found on a device or in a folder. */
public record DetectedMap(Path path, MapFileName name) {
}
