package io.github.fernandoglatz.igpsmap.update;

import java.net.URI;
import java.util.List;

/**
 * A published release: its version and tag, its page and the releases list on GitHub, the release
 * notes (plain text) and the downloadable files.
 */
public record ReleaseInfo(Version version, String tag, URI page, URI releasesPage, String notes, List<Asset> assets) {

    /** A release file; {@code sha256} is the lower-case hex digest GitHub publishes, or null when absent. */
    public record Asset(String name, long size, URI url, String sha256) {

        public Asset(String name, long size, URI url) {
            this(name, size, url, null);
        }
    }

    public ReleaseInfo {
        assets = List.copyOf(assets);
    }
}
