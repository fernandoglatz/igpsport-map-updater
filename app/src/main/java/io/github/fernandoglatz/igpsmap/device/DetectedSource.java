package io.github.fernandoglatz.igpsmap.device;

import java.nio.file.Path;
import java.util.List;

/** A mounted device (or folder) that holds IGPSPORT map files. */
public record DetectedSource(String label, Path root, List<DetectedMap> maps) {
}
