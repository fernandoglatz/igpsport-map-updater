package io.github.fernandoglatz.igpsmap.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny local HTTP server for tests. Records every request. */
public final class StubServer implements AutoCloseable {

    public record Request(String method, String path, String userAgent, String range) {
    }

    private final HttpServer server;
    public final List<Request> requests = new CopyOnWriteArrayList<>();

    public StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    public StubServer handle(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("User-Agent"),
                    exchange.getRequestHeaders().getFirst("Range")));
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        return this;
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    public static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Serves {@code whole}, honouring a single {@code Range: bytes=a-b}, {@code bytes=a-} or
     * {@code bytes=-n} header with 206 and Content-Range like a static file server does.
     */
    public static void sendRange(HttpExchange exchange, byte[] whole) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        java.util.regex.Matcher m = range == null ? null
                : java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range.trim());
        if (m == null || !m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
            send(exchange, 200, whole);
            return;
        }
        long from;
        long to;
        if (m.group(1).isEmpty()) {
            from = Math.max(0, whole.length - Long.parseLong(m.group(2)));
            to = whole.length - 1;
        } else {
            from = Long.parseLong(m.group(1));
            to = m.group(2).isEmpty() ? whole.length - 1 : Math.min(Long.parseLong(m.group(2)), whole.length - 1);
        }
        if (from >= whole.length || from > to) {
            exchange.getResponseHeaders().add("Content-Range", "bytes */" + whole.length);
            send(exchange, 416, new byte[0]);
            return;
        }
        exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + whole.length);
        send(exchange, 206, java.util.Arrays.copyOfRange(whole, (int) from, (int) to + 1));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
