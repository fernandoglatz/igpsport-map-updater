package io.github.fernandoglatz.igpsmap.update;

import io.github.fernandoglatz.igpsmap.net.HttpFetcher;
import io.github.fernandoglatz.igpsmap.net.ProgressListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Downloads a release file and hands it to the system installer for this kind of installation. The app
 * never replaces its own files; the installer (or the user, for portable folders) does.
 */
public final class UpdateInstaller {

    /** What the app should do after the hand-off. */
    public enum Next {
        /** The installer is running and needs the app's files unlocked: close the app. */
        EXIT_APP,
        /** The new version is installed: offer to restart. */
        OFFER_RESTART,
        /** The user finishes by hand: show the downloaded file's folder. */
        SHOW_FOLDER,
        /** A helper replaces the portable app once it exits and starts the new version: close now. */
        RESTARTING
    }

    /**
     * Where the running app lives: the portable folder (when started from one), this process's id and the
     * app's data folder (which must survive a portable update).
     */
    public record Environment(Optional<PortableApp> portable, long pid, Path dataRoot) {

        public static Environment current(Path dataRoot) {
            return new Environment(PortableApp.current(), ProcessHandle.current().pid(), dataRoot);
        }

        static Environment none() {
            return new Environment(Optional.empty(), ProcessHandle.current().pid(), null);
        }
    }

    public record Handoff(Next next, String message, Path file) {
    }

    /** Starts a process without waiting for it (a seam for tests). */
    @FunctionalInterface
    public interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    private final HttpFetcher fetcher;
    private final Path updatesDir;
    private final ProcessStarter starter;
    private final Environment environment;

    public UpdateInstaller(HttpFetcher fetcher, Path updatesDir, ProcessStarter starter) {
        this(fetcher, updatesDir, starter, Environment.none());
    }

    public UpdateInstaller(HttpFetcher fetcher, Path updatesDir, ProcessStarter starter, Environment environment) {
        this.fetcher = fetcher;
        this.updatesDir = updatesDir;
        this.starter = starter;
        this.environment = environment;
    }

    /**
     * Starts processes for updates and restarts. They must not inherit the jpackage launcher's own marker
     * variable: a launcher started with it prints the java usage text and exits.
     */
    public static ProcessStarter systemStarter() {
        return command -> {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            cleanEnvironment(builder.environment());
            return builder.start();
        };
    }

    /** Removes variables the running jpackage launcher set for itself. */
    static void cleanEnvironment(java.util.Map<String, String> environment) {
        environment.remove("_JPACKAGE_LAUNCHER");
    }

    private static final java.util.regex.Pattern RELEASE_FILE =
            java.util.regex.Pattern.compile("^igpsport-map-updater-(\\d+\\.\\d+\\.\\d+)-.+");

    /** Deletes downloaded release files of versions up to {@code installed}: they are installed already. */
    public void cleanUp(Version installed) throws IOException {
        if (!Files.isDirectory(updatesDir)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.list(updatesDir)) {
            for (Path file : files.toList()) {
                java.util.regex.Matcher m = RELEASE_FILE.matcher(file.getFileName().toString());
                if (m.matches() && Version.parse(m.group(1)).filter(v -> v.compareTo(installed) <= 0).isPresent()
                        && Files.isRegularFile(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** Downloads {@code asset} into the updates folder; a size different from the release's is an error. */
    public Path download(ReleaseInfo.Asset asset, ProgressListener progress) throws IOException {
        String name = Path.of(asset.name()).getFileName().toString(); // never a path from the network
        Path target = updatesDir.resolve(name);
        fetcher.download(asset.url(), target, progress);
        if (asset.size() >= 0 && Files.size(target) != asset.size()) {
            long got = Files.size(target);
            Files.deleteIfExists(target);
            throw new IOException("Download incomplete: got " + got + " of " + asset.size() + " bytes");
        }
        // The file is run as an installer, so it must be exactly the one GitHub lists for the release.
        if (asset.sha256() != null && !asset.sha256().equals(sha256(target))) {
            Files.deleteIfExists(target);
            throw new IOException("Download failed the checksum check; nothing was installed. Try again later.");
        }
        return target;
    }

    private static String sha256(Path file) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Starts the installer for {@code type}; for .deb waits for the password prompt and installation. */
    public Handoff handOff(Path file, PackageType type) throws IOException, InterruptedException {
        String path = file.toAbsolutePath().toString();
        return switch (type) {
            case MSI -> {
                starter.start(List.of("msiexec", "/i", path));
                yield new Handoff(Next.EXIT_APP,
                        "The installer has started. The app closes now so it can be updated.", file);
            }
            case DMG -> {
                starter.start(List.of("open", path));
                yield new Handoff(Next.EXIT_APP, "The disk image is open: drag IGPSPORT Map Updater to Applications "
                        + "and replace the old one. The app closes now.", file);
            }
            case DEB -> installDeb(file, path);
            case APP_IMAGE -> replacePortable(file);
            case OTHER, SOURCE -> new Handoff(Next.SHOW_FOLDER,
                    "The new version was downloaded. Install it the same way you installed this one.", file);
        };
    }

    /** Extracts the archive next to the app and starts the helper that swaps the folders after exit. */
    private Handoff replacePortable(Path file) throws IOException {
        Handoff manual = new Handoff(Next.SHOW_FOLDER, "The new version was downloaded. Close the app, extract the "
                + "archive and replace the old app folder with the new one. Your settings and maps are kept.", file);
        if (environment.portable().isEmpty()) {
            return manual;
        }
        PortableApp app = environment.portable().get();
        Path dataRoot = environment.dataRoot();
        if (dataRoot != null && dataRoot.toAbsolutePath().normalize().startsWith(app.root())) {
            return manual; // replacing the folder would also replace the app's data
        }
        if (!Files.isWritable(app.root()) || !Files.isWritable(app.root().getParent())) {
            return manual;
        }
        Path staging = PortableUpdate.stagingDir(app);
        try {
            PortableUpdate.deleteRecursively(staging);
            PortableUpdate.extract(file, staging);
            Optional<Path> newRoot = PortableUpdate.findNewRoot(staging, app.root().relativize(app.launcher()));
            if (newRoot.isEmpty()) {
                PortableUpdate.deleteRecursively(staging);
                return manual;
            }
            Path helper = PortableUpdate.writeHelper(updatesDir, app.platform());
            starter.start(PortableUpdate.helperCommand(app, helper, environment.pid(), newRoot.get(), staging));
            return new Handoff(Next.RESTARTING, "The update is being installed; the app restarts in a moment.", file);
        } catch (IOException e) {
            PortableUpdate.deleteRecursively(staging);
            throw e;
        }
    }

    private Handoff installDeb(Path file, String path) throws InterruptedException {
        String manual = "Install it from a terminal with: sudo apt install " + path;
        try {
            Process process = starter.start(List.of("pkexec", "apt-get", "install", "-y", path));
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            int exit = process.waitFor();
            if (exit == 0) {
                return new Handoff(Next.OFFER_RESTART, "The new version is installed. Restart the app to use it.", file);
            }
            return new Handoff(Next.SHOW_FOLDER, "The installation was cancelled or failed (exit " + exit + "). "
                    + manual, file);
        } catch (IOException e) {
            return new Handoff(Next.SHOW_FOLDER, "The new version was downloaded. " + manual, file);
        }
    }
}
