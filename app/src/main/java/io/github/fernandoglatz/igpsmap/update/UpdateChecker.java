package io.github.fernandoglatz.igpsmap.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Asks GitHub for the latest published release of the app. */
public final class UpdateChecker {

    public static final URI DEFAULT_API =
            URI.create("https://api.github.com/repos/fernandoglatz/igpsport-map-updater/releases/latest");

    private final HttpFetcher fetcher;
    private final URI api;

    public UpdateChecker(HttpFetcher fetcher, URI api) {
        this.fetcher = fetcher;
        this.api = api;
    }

    /** The latest release, or empty when the answer has no usable version; throws on network/HTTP errors. */
    public Optional<ReleaseInfo> latest() throws IOException {
        return parse(fetcher.getText(api));
    }

    /** {@code release} if it's newer than {@code current} and not the version the user chose to skip. */
    public static Optional<ReleaseInfo> newer(ReleaseInfo release, Version current, String skippedVersion) {
        boolean skipped = Version.parse(skippedVersion).map(release.version()::equals).orElse(false);
        return release.version().compareTo(current) > 0 && !skipped ? Optional.of(release) : Optional.empty();
    }

    /** The release file for this kind of installation, e.g. "...-linux-x64.deb" for a .deb install. */
    public static Optional<ReleaseInfo.Asset> asset(ReleaseInfo release, PackageType type, Platform platform) {
        Optional<String> suffix = platform.assetSuffix(type);
        return suffix.flatMap(s -> release.assets().stream().filter(a -> a.name().endsWith(s)).findFirst());
    }

    static Optional<ReleaseInfo> parse(String json) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return Optional.empty();
        }
        if (bool(o, "draft") || bool(o, "prerelease")) {
            return Optional.empty();
        }
        String tag = text(o, "tag_name");
        Optional<Version> version = Version.parse(tag);
        URI page = uri(text(o, "html_url"));
        if (version.isEmpty() || page == null) {
            return Optional.empty();
        }
        String path = page.toString();
        int tagStart = path.indexOf("/tag/");
        URI releasesPage = tagStart > 0 ? URI.create(path.substring(0, tagStart)) : page;
        List<ReleaseInfo.Asset> assets = new ArrayList<>();
        JsonElement list = o.get("assets");
        if (list != null && list.isJsonArray()) {
            for (JsonElement element : (JsonArray) list) {
                if (element.isJsonObject()) {
                    JsonObject a = element.getAsJsonObject();
                    URI url = uri(text(a, "browser_download_url"));
                    long size = a.has("size") && a.get("size").isJsonPrimitive() ? a.get("size").getAsLong() : -1;
                    if (url != null && !text(a, "name").isEmpty()) {
                        assets.add(new ReleaseInfo.Asset(text(a, "name"), size, url, sha256(text(a, "digest"))));
                    }
                }
            }
        }
        String notes = text(o, "body").replace("\r\n", "\n").strip();
        return Optional.of(new ReleaseInfo(version.get(), tag, page, releasesPage, notes, assets));
    }

    /** "sha256:<64 hex>" as published by GitHub, normalised to lower case; null otherwise. */
    private static String sha256(String digest) {
        String prefix = "sha256:";
        if (!digest.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String hex = digest.substring(prefix.length()).strip().toLowerCase(java.util.Locale.ROOT);
        return hex.matches("[0-9a-f]{64}") ? hex : null;
    }

    private static URI uri(String text) {
        try {
            URI uri = URI.create(text);
            return "https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }
}
