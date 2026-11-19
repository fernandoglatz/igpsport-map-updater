package io.github.fernandoglatz.igpsmap.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** Small HTTP helper: text GET, HEAD Last-Modified and resumable file downloads. */
public final class HttpFetcher {

    /** Version comes from the jar manifest (set by the release build); "dev" when run from classes. */
    public static final String USER_AGENT = userAgent(HttpFetcher.class.getPackage().getImplementationVersion());

    static String userAgent(String version) {
        return "igpsport-map-updater/" + (version == null || version.isBlank() ? "dev" : version)
                + " (+https://github.com/fernandoglatz/igpsport-map-updater)";
    }

    /** The freshness check is optional, so it must not hold a download up for long. */
    private static final Duration HEAD_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client;
    private final String userAgent;
    private final Duration timeout;

    public HttpFetcher(HttpClient client, String userAgent, Duration timeout) {
        this.client = client;
        this.userAgent = userAgent;
        this.timeout = timeout;
    }

    public static HttpFetcher createDefault() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        return new HttpFetcher(client, USER_AGENT, Duration.ofSeconds(60));
    }

    /** Same client and User-Agent with another per-request timeout (e.g. for slow Overpass queries). */
    public HttpFetcher withTimeout(Duration newTimeout) {
        return new HttpFetcher(client, userAgent, newTimeout);
    }

    public String getText(URI uri) throws IOException {
        HttpResponse<byte[]> response = send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        requireSuccess(uri, response.statusCode());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    public Optional<Instant> lastModified(URI uri) {
        try {
            HttpResponse<Void> response = send(
                    request(uri).timeout(HEAD_TIMEOUT.compareTo(timeout) < 0 ? HEAD_TIMEOUT : timeout)
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            return response.headers().firstValue("Last-Modified")
                    .map(v -> ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Downloads to {@code target + ".part"} (resuming it when present) and moves it into place
     * once complete. The target only ever exists as a complete file.
     */
    public void download(URI uri, Path target, ProgressListener listener) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Path validatorFile = target.resolveSibling(target.getFileName() + ".part.validator");
        Files.createDirectories(target.toAbsolutePath().getParent());
        Optional<String> validator = Files.exists(validatorFile)
                ? Optional.of(Files.readString(validatorFile).strip()).filter(v -> !v.isEmpty())
                : Optional.empty();
        if (Files.exists(part) && validator.isEmpty()) {
            Files.delete(part); // cannot prove the partial file matches the server's current version
        }
        long existing = Files.exists(part) ? Files.size(part) : 0;

        HttpRequest.Builder builder = request(uri).GET();
        if (existing > 0) {
            // If-Range: the server sends the remaining bytes only if the file is unchanged,
            // otherwise the full new file (200), which replaces the stale part.
            builder.header("Range", "bytes=" + existing + "-");
            builder.header("If-Range", validator.get());
        }
        HttpResponse<InputStream> response = send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status == 416 && existing > 0) {
                // Range not satisfiable: the part file is already complete or stale. Start over.
                Files.deleteIfExists(part);
                Files.deleteIfExists(validatorFile);
                download(uri, target, listener);
                return;
            }
            requireSuccess(uri, status);
            boolean resumed = status == 206;
            long offset = resumed ? existing : 0;
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            long total = length < 0 ? -1 : offset + length;

            if (!resumed) {
                Optional<String> newValidator = response.headers().firstValue("ETag")
                        .filter(tag -> !tag.startsWith("W/"))
                        .or(() -> response.headers().firstValue("Last-Modified"));
                if (newValidator.isPresent()) {
                    Files.writeString(validatorFile, newValidator.get());
                } else {
                    Files.deleteIfExists(validatorFile);
                }
            }
            StandardOpenOption mode = resumed ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
            try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode)) {
                byte[] buffer = new byte[64 * 1024];
                long done = offset;
                listener.onProgress(done, total);
                int n;
                while ((n = body.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    done += n;
                    listener.onProgress(done, total);
                }
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(validatorFile);
    }

    /**
     * Result of a range request. {@code partial} is false when the server ignored the range and sent
     * the whole file, which is then in {@code body}.
     */
    public record RangeResult(byte[] body, long totalSize, boolean partial) {
    }

    /** GET with {@code Range: bytes=<spec>}, e.g. {@code "-65536"} (last bytes) or {@code "100-199"}. */
    public RangeResult readRange(URI uri, String spec) throws IOException {
        HttpResponse<byte[]> response = send(request(uri).GET().header("Range", "bytes=" + spec).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        requireSuccess(uri, response.statusCode());
        byte[] body = response.body();
        if (response.statusCode() != 206) {
            return new RangeResult(body, body.length, false);
        }
        return new RangeResult(body, totalFromContentRange(uri, response.headers().firstValue("Content-Range")), true);
    }

    /** Streams bytes {@code from..toInclusive}; fails unless the server answers with that range (206). */
    public InputStream openRange(URI uri, long from, long toInclusive) throws IOException {
        HttpResponse<InputStream> response = send(
                request(uri).GET().header("Range", "bytes=" + from + "-" + toInclusive).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status != 206) {
            response.body().close();
            requireSuccess(uri, status);
            throw new IOException("Server ignored the byte range request for " + uri);
        }
        return response.body();
    }

    private static long totalFromContentRange(URI uri, Optional<String> header) throws IOException {
        // Content-Range: bytes 900-999/1000
        String value = header.orElseThrow(() -> new IOException("Missing Content-Range from " + uri));
        int slash = value.lastIndexOf('/');
        try {
            return Long.parseLong(value.substring(slash + 1).trim());
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            throw new IOException("Unknown total size in Content-Range '" + value + "' from " + uri, e);
        }
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", userAgent);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return client.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while requesting " + request.uri(), e);
        }
    }

    private static void requireSuccess(URI uri, int status) throws HttpStatusException {
        if (status / 100 != 2) {
            throw new HttpStatusException(uri, status);
        }
    }
}
