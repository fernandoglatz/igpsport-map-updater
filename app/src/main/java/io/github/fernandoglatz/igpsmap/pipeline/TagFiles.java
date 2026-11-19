package io.github.fernandoglatz.igpsmap.pipeline;

import java.nio.file.Path;

/** The Mapsforge tag mapping and the osmosis tag transform files. */
public record TagFiles(Path tagConf, Path transform) {
}
