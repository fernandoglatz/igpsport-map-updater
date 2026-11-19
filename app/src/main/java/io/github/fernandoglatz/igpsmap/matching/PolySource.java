package io.github.fernandoglatz.igpsmap.matching;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import java.net.URI;

/** Where the clipping polygon for a job comes from. */
public sealed interface PolySource permits PolySource.Remote, PolySource.FromBoundingBox {

    String label();

    /** A {@code .poly} file to download. */
    record Remote(URI url, String label) implements PolySource {
    }

    /** A rectangle generated from the original file's GEOCODE. */
    record FromBoundingBox(BoundingBox box) implements PolySource {
        @Override
        public String label() {
            return "bounding box";
        }
    }
}
