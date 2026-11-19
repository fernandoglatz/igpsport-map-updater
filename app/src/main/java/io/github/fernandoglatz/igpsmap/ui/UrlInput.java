package io.github.fernandoglatz.igpsmap.ui;

import java.net.URI;

/** Validation for URLs typed into the edit dialog. */
final class UrlInput {

    private UrlInput() {
    }

    static boolean isHttpUrl(String text) {
        // A comma would split the row in maps.csv.
        if (text == null || text.isBlank() || text.contains(",")) {
            return false;
        }
        try {
            URI uri = URI.create(text.trim());
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getPath() != null && uri.getPath().length() > 1;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
