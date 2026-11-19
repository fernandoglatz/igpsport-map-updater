package io.github.fernandoglatz.igpsmap.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.StubServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateInstallerTest {

    private static final byte[] PACKAGE = "installer bytes".repeat(100).getBytes();

    @TempDir
    Path dir;

    private StubServer server;
    private final List<List<String>> started = new ArrayList<>();
    private int exitCode;
    private boolean pkexecMissing;

    /** A finished process with the configured exit code. */
    private final class DoneProcess extends Process {
        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
        }
    }

    private UpdateInstaller installer() {
        HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
        return new UpdateInstaller(fetcher, dir.resolve("updates"), command -> {
            if (pkexecMissing && command.get(0).equals("pkexec")) {
                throw new IOException("Cannot run program \"pkexec\": error=2, No such file or directory");
            }
            started.add(command);
            return new DoneProcess();
        });
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new StubServer();
        server.handle("/pkg.deb", ex -> StubServer.send(ex, 200, PACKAGE));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private ReleaseInfo.Asset asset(long size) {
        return new ReleaseInfo.Asset("igpsport-map-updater-1.2.0-linux-x64.deb", size, server.uri("/pkg.deb"));
    }

    @Test
    void startedProcessesDoNotInheritTheLauncherMarker() {
        // The jpackage launcher sets _JPACKAGE_LAUNCHER for itself; a new launcher that inherits it prints
        // the java usage text and exits instead of starting the app.
        java.util.Map<String, String> env = new java.util.HashMap<>(java.util.Map.of("_JPACKAGE_LAUNCHER", "x", "HOME", "/h"));
        UpdateInstaller.cleanEnvironment(env);
        assertEquals(java.util.Map.of("HOME", "/h"), env);
    }

    @Test
    void helperScriptsAlsoDropTheLauncherMarker() throws IOException {
        Path updates = dir.resolve("updates");
        assertTrue(Files.readString(PortableUpdate.writeHelper(updates, new Platform("linux", "x64")))
                .contains("unset _JPACKAGE_LAUNCHER"));
        assertTrue(Files.readString(PortableUpdate.writeHelper(updates, new Platform("windows", "x64")))
                .contains("Env:_JPACKAGE_LAUNCHER"));
    }

    @Test
    void cleanUpRemovesDownloadsOfInstalledVersionsOnly() throws IOException {
        Path updates = Files.createDirectories(dir.resolve("updates"));
        for (String name : List.of("igpsport-map-updater-1.0.0-linux-x64.deb", "igpsport-map-updater-1.1.0-linux-x64.tar.gz",
                "igpsport-map-updater-1.2.0-linux-x64.deb", "replace-app.sh", "update.log", "notes.txt")) {
            Files.writeString(updates.resolve(name), "x");
        }

        installer().cleanUp(new Version(1, 1, 0));

        try (var left = Files.list(updates)) {
            assertEquals(List.of("igpsport-map-updater-1.2.0-linux-x64.deb", "notes.txt", "replace-app.sh", "update.log"),
                    left.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void cleanUpWithoutAnUpdatesFolderDoesNothing() throws IOException {
        installer().cleanUp(new Version(1, 0, 0));
        assertFalse(Files.exists(dir.resolve("updates")));
    }

    @Test
    void downloadsTheReleaseFile() throws IOException {
        long[] last = new long[1];
        Path file = installer().download(asset(PACKAGE.length), (done, total) -> last[0] = done);

        assertEquals(dir.resolve("updates/igpsport-map-updater-1.2.0-linux-x64.deb"), file);
        assertArrayEquals(PACKAGE, Files.readAllBytes(file));
        assertEquals(PACKAGE.length, last[0]);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void matchingChecksumIsAccepted() throws Exception {
        ReleaseInfo.Asset asset = new ReleaseInfo.Asset("igpsport-map-updater-1.2.0-linux-x64.deb", PACKAGE.length,
                server.uri("/pkg.deb"), sha256(PACKAGE));
        assertArrayEquals(PACKAGE, Files.readAllBytes(installer().download(asset, (d, t) -> { })));
    }

    @Test
    void checksumMismatchFailsAndRemovesTheFile() throws Exception {
        ReleaseInfo.Asset asset = new ReleaseInfo.Asset("igpsport-map-updater-1.2.0-linux-x64.deb", PACKAGE.length,
                server.uri("/pkg.deb"), sha256("something else".getBytes()));
        IOException e = assertThrows(IOException.class, () -> installer().download(asset, (d, t) -> { }));
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("updates/igpsport-map-updater-1.2.0-linux-x64.deb")));
    }

    @Test
    void sizeMismatchFailsAndRemovesTheFile() {
        IOException e = assertThrows(IOException.class, () -> installer().download(asset(PACKAGE.length + 1), (d, t) -> { }));
        assertTrue(e.getMessage().contains("incomplete"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("updates/igpsport-map-updater-1.2.0-linux-x64.deb")));
    }

    @Test
    void msiStartsTheWindowsInstallerAndClosesTheApp() throws Exception {
        Path msi = dir.resolve("a.msi");
        UpdateInstaller.Handoff handoff = installer().handOff(msi, PackageType.MSI);
        assertEquals(List.of(List.of("msiexec", "/i", msi.toString())), started);
        assertEquals(UpdateInstaller.Next.EXIT_APP, handoff.next());
    }

    @Test
    void dmgOpensTheDiskImageAndClosesTheApp() throws Exception {
        Path dmg = dir.resolve("a.dmg");
        UpdateInstaller.Handoff handoff = installer().handOff(dmg, PackageType.DMG);
        assertEquals(List.of(List.of("open", dmg.toString())), started);
        assertEquals(UpdateInstaller.Next.EXIT_APP, handoff.next());
        assertTrue(handoff.message().contains("Applications"), handoff.message());
    }

    @Test
    void debInstallsWithThePasswordPromptAndOffersARestart() throws Exception {
        Path deb = dir.resolve("a.deb");
        UpdateInstaller.Handoff handoff = installer().handOff(deb, PackageType.DEB);
        assertEquals(List.of(List.of("pkexec", "apt-get", "install", "-y", deb.toAbsolutePath().toString())), started);
        assertEquals(UpdateInstaller.Next.OFFER_RESTART, handoff.next());
    }

    @Test
    void refusedOrFailedDebInstallShowsTheCommand() throws Exception {
        exitCode = 126; // pkexec: authentication dismissed
        Path deb = dir.resolve("a.deb");
        UpdateInstaller.Handoff handoff = installer().handOff(deb, PackageType.DEB);
        assertEquals(UpdateInstaller.Next.SHOW_FOLDER, handoff.next());
        assertTrue(handoff.message().contains("sudo apt install " + deb.toAbsolutePath()), handoff.message());
    }

    @Test
    void missingPkexecShowsTheCommand() throws Exception {
        pkexecMissing = true;
        UpdateInstaller.Handoff handoff = installer().handOff(dir.resolve("a.deb"), PackageType.DEB);
        assertEquals(UpdateInstaller.Next.SHOW_FOLDER, handoff.next());
        assertTrue(handoff.message().contains("sudo apt install"), handoff.message());
    }

    @Test
    void portableWithoutAKnownLauncherFallsBackToTheFolder() throws Exception {
        UpdateInstaller.Handoff handoff = installer().handOff(dir.resolve("a.tar.gz"), PackageType.APP_IMAGE);
        assertEquals(List.of(), started);
        assertEquals(UpdateInstaller.Next.SHOW_FOLDER, handoff.next());
        assertTrue(handoff.message().contains("replace"), handoff.message());
    }

    private static final String NAME = "IGPSPORT Map Updater";

    /** A portable Windows-style install in apps/IGPSPORT, with its data folder elsewhere. */
    private UpdateInstaller portableInstaller(Path dataRoot) throws IOException {
        Path root = Files.createDirectories(dir.resolve("apps/IGPSPORT"));
        Files.writeString(root.resolve(NAME + ".exe"), "old");
        PortableApp app = new PortableApp(root, root.resolve(NAME + ".exe"), new Platform("windows", "x64"));
        HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "t", Duration.ofSeconds(5));
        return new UpdateInstaller(fetcher, dir.resolve("updates"), command -> {
            started.add(command);
            return new DoneProcess();
        }, new UpdateInstaller.Environment(java.util.Optional.of(app), 4242, dataRoot));
    }

    private Path zip(String... entries) throws IOException {
        Path file = dir.resolve("updates/igpsport-map-updater-1.2.0-windows-x64.zip");
        Files.createDirectories(file.getParent());
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            for (String entry : entries) {
                out.putNextEntry(new java.util.zip.ZipEntry(entry));
                out.write("new".getBytes());
                out.closeEntry();
            }
        }
        return file;
    }

    @Test
    void portableUpdatesAreExtractedAndSwappedSilently() throws Exception {
        Path archive = zip(NAME + "/" + NAME + ".exe", NAME + "/app/a.jar");

        UpdateInstaller.Handoff handoff = portableInstaller(dir.resolve("data")).handOff(archive, PackageType.APP_IMAGE);

        assertEquals(UpdateInstaller.Next.RESTARTING, handoff.next());
        Path staging = dir.resolve("apps/.IGPSPORT.update");
        assertEquals("new", Files.readString(staging.resolve(NAME + "/app/a.jar")));
        assertEquals(1, started.size());
        List<String> command = started.get(0);
        assertEquals("powershell.exe", command.get(0));
        assertTrue(command.contains(dir.resolve("apps/IGPSPORT").toString()), command.toString());
        assertTrue(command.contains(staging.resolve(NAME).toString()), command.toString());
        assertTrue(command.contains("4242"), command.toString());
        assertTrue(Files.exists(dir.resolve("updates/replace-app.ps1")));
    }

    @Test
    void dataInsideTheAppFolderIsNeverSwappedAway() throws Exception {
        Path archive = zip(NAME + "/" + NAME + ".exe");
        UpdateInstaller.Handoff handoff = portableInstaller(dir.resolve("apps/IGPSPORT/data"))
                .handOff(archive, PackageType.APP_IMAGE);
        assertEquals(UpdateInstaller.Next.SHOW_FOLDER, handoff.next());
        assertEquals(List.of(), started);
    }

    @Test
    void archiveWithoutTheLauncherIsNotInstalled() throws Exception {
        Path archive = zip("something-else/readme.txt");
        UpdateInstaller.Handoff handoff = portableInstaller(dir.resolve("data")).handOff(archive, PackageType.APP_IMAGE);
        assertEquals(UpdateInstaller.Next.SHOW_FOLDER, handoff.next());
        assertEquals(List.of(), started);
        assertFalse(Files.exists(dir.resolve("apps/.IGPSPORT.update")), "staging folder left behind");
        assertEquals("old", Files.readString(dir.resolve("apps/IGPSPORT/" + NAME + ".exe")));
    }
}
