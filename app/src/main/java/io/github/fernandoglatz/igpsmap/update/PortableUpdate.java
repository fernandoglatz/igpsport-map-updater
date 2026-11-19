package io.github.fernandoglatz.igpsmap.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Silent update of a portable installation: the new version is extracted next to the app folder, and a
 * helper script swaps the folders once the app has exited, then starts the new version.
 */
final class PortableUpdate {

    private PortableUpdate() {
    }

    /** Extracts a release archive (.zip with Java, .tar.gz with the system tar, which keeps permissions). */
    static void extract(Path archive, Path dir) throws IOException {
        Files.createDirectories(dir);
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            extractZip(archive, dir);
        } else {
            extractTar(archive, dir);
        }
    }

    /** The folder inside {@code staging} that holds the launcher at {@code launcherRelative}. */
    static Optional<Path> findNewRoot(Path staging, Path launcherRelative) throws IOException {
        try (Stream<Path> children = Files.list(staging)) {
            return children.filter(Files::isDirectory)
                    .filter(child -> Files.isRegularFile(child.resolve(launcherRelative)))
                    .findFirst();
        }
    }

    /** Hidden folder next to the app, on the same disk, so the final swap is a rename. */
    static Path stagingDir(PortableApp app) {
        return app.root().resolveSibling("." + app.root().getFileName() + ".update");
    }

    /** Copies the helper script for this platform into {@code dir}. */
    static Path writeHelper(Path dir, Platform platform) throws IOException {
        String name = platform.os().equals("windows") ? "replace-app.ps1" : "replace-app.sh";
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        try (InputStream in = PortableUpdate.class.getResourceAsStream("/update/" + name)) {
            if (in == null) {
                throw new IOException("Missing update helper " + name);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** Command that runs the helper detached from the app, so it outlives it. */
    static List<String> helperCommand(PortableApp app, Path script, long pid, Path newRoot, Path staging) {
        if (app.platform().os().equals("windows")) {
            return List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-File", script.toString(), "-ProcessId", Long.toString(pid), "-Old", app.root().toString(),
                    "-New", newRoot.toString(), "-Launcher", app.launcher().toString(), "-Staging", staging.toString());
        }
        return List.of("nohup", "/bin/sh", script.toString(), Long.toString(pid), app.root().toString(),
                newRoot.toString(), app.launcher().toString(), staging.toString());
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void extractZip(Path archive, Path dir) throws IOException {
        Path base = dir.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = base.resolve(entry.getName().replace('\\', '/')).normalize();
                if (!target.startsWith(base) || target.equals(base)) {
                    throw new IOException("Archive entry outside the target folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void extractTar(Path archive, Path dir) throws IOException {
        List<String> command = new ArrayList<>(List.of("tar", "-xzf", archive.toString(), "-C", dir.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        try {
            if (process.waitFor() != 0) {
                throw new IOException("Could not extract " + archive.getFileName() + ": " + output.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("Interrupted while extracting " + archive.getFileName(), e);
        }
    }
}
