package io.github.fernandoglatz.igpsmap.net;

import java.io.IOException;
import java.net.URI;

/** Non-2xx HTTP response. */
public class HttpStatusException extends IOException {

    private final int status;

    public HttpStatusException(URI uri, int status) {
        super("HTTP " + status + " for " + withoutQuery(uri));
        this.status = status;
    }

    private static String withoutQuery(URI uri) {
        String text = uri.toString();
        int q = text.indexOf('?');
        return q < 0 ? text : text.substring(0, q);
    }

    public int status() {
        return status;
    }
}
