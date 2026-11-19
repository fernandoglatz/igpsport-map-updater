package io.github.fernandoglatz.igpsmap.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.StubServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

    private static final Version V110 = new Version(1, 1, 0);

    static String latestJson() throws IOException {
        try (InputStream in = UpdateCheckerTest.class.getResourceAsStream("/release-latest.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheLatestRelease() throws IOException {
        ReleaseInfo release = UpdateChecker.parse(latestJson()).orElseThrow();

        assertEquals(new Version(1, 2, 0), release.version());
        assertEquals("v1.2.0", release.tag());
        assertEquals(URI.create("https://github.com/fernandoglatz/igpsport-map-updater/releases/tag/v1.2.0"), release.page());
        assertEquals(URI.create("https://github.com/fernandoglatz/igpsport-map-updater/releases"), release.releasesPage());
        assertTrue(release.notes().startsWith("## What's new\n- In-app updates"), release.notes());
        assertEquals(6, release.assets().size());
        assertEquals(new ReleaseInfo.Asset("igpsport-map-updater-1.2.0-linux-x64.deb", 40735878,
                URI.create("https://github.com/fernandoglatz/igpsport-map-updater/releases/download/v1.2.0/"
                        + "igpsport-map-updater-1.2.0-linux-x64.deb"),
                "e0350ed1a57ad0efe2314905b0a71d4b94b86b1f857b40cbfbb0c512a3e0268d"), release.assets().get(0));
        assertEquals(null, release.assets().get(1).sha256(), "assets without a digest keep the size check only");
    }

    @Test
    void releasesWithoutAVersionTagAreIgnored() {
        assertTrue(UpdateChecker.parse("{\"tag_name\":\"latest\",\"html_url\":\"https://h/r/tag/latest\",\"assets\":[]}")
                .isEmpty());
        assertTrue(UpdateChecker.parse("{\"message\":\"Not Found\"}").isEmpty());
        assertTrue(UpdateChecker.parse("<html>").isEmpty());
    }

    @Test
    void draftsAndPrereleasesAreIgnored() {
        assertTrue(UpdateChecker.parse("{\"tag_name\":\"v9.0.0\",\"prerelease\":true,\"html_url\":\"https://h/r/tag/v9.0.0\"}")
                .isEmpty());
        assertTrue(UpdateChecker.parse("{\"tag_name\":\"v9.0.0\",\"draft\":true,\"html_url\":\"https://h/r/tag/v9.0.0\"}")
                .isEmpty());
    }

    @Test
    void offersOnlyNewerVersionsThatWereNotSkipped() throws IOException {
        ReleaseInfo release = UpdateChecker.parse(latestJson()).orElseThrow();
        assertEquals(Optional.of(release), UpdateChecker.newer(release, V110, null));
        assertTrue(UpdateChecker.newer(release, new Version(1, 2, 0), null).isEmpty());
        assertTrue(UpdateChecker.newer(release, new Version(2, 0, 0), null).isEmpty());
        assertTrue(UpdateChecker.newer(release, V110, "1.2.0").isEmpty());
        assertEquals(Optional.of(release), UpdateChecker.newer(release, V110, "1.1.5"));
    }

    @Test
    void fetchesFromTheApi() throws IOException {
        try (StubServer server = new StubServer()) {
            String json = latestJson();
            server.handle("/repos/o/r/releases/latest", ex -> StubServer.send(ex, 200, json.getBytes(StandardCharsets.UTF_8)));
            HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
            UpdateChecker checker = new UpdateChecker(fetcher, server.uri("/repos/o/r/releases/latest"));

            assertEquals(new Version(1, 2, 0), checker.latest().orElseThrow().version());
            assertEquals(List.of("/repos/o/r/releases/latest"), server.requests.stream().map(StubServer.Request::path).toList());
        }
    }

    @Test
    void apiErrorsAreReported() throws IOException {
        try (StubServer server = new StubServer()) {
            server.handle("/latest", ex -> StubServer.send(ex, 403, "{\"message\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8)));
            UpdateChecker checker = new UpdateChecker(new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5)),
                    server.uri("/latest"));
            assertThrows(IOException.class, checker::latest);
        }
    }

    @Test
    void picksTheFileForThisInstallation() throws IOException {
        ReleaseInfo release = UpdateChecker.parse(latestJson()).orElseThrow();
        Platform linux = new Platform("linux", "x64");
        assertEquals("igpsport-map-updater-1.2.0-linux-x64.deb", UpdateChecker.asset(release, PackageType.DEB, linux).orElseThrow().name());
        assertEquals("igpsport-map-updater-1.2.0-linux-x64.tar.gz",
                UpdateChecker.asset(release, PackageType.APP_IMAGE, linux).orElseThrow().name());
        assertEquals("igpsport-map-updater-1.2.0-windows-x64.msi",
                UpdateChecker.asset(release, PackageType.MSI, new Platform("windows", "x64")).orElseThrow().name());
        assertEquals("igpsport-map-updater-1.2.0-macos-arm64.dmg",
                UpdateChecker.asset(release, PackageType.DMG, new Platform("macos", "arm64")).orElseThrow().name());
    }

    @Test
    void noFileForSourceRunsOrMissingPlatforms() throws IOException {
        ReleaseInfo release = UpdateChecker.parse(latestJson()).orElseThrow();
        assertTrue(UpdateChecker.asset(release, PackageType.SOURCE, new Platform("linux", "x64")).isEmpty());
        assertTrue(UpdateChecker.asset(release, PackageType.DMG, new Platform("macos", "x64")).isEmpty());
        assertTrue(UpdateChecker.asset(release, PackageType.DEB, new Platform("linux", "arm64")).isEmpty());
    }
}
