package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.update.UpdateInstaller;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Opens a folder in the system's file manager. JavaFX's HostServices.showDocument hands file: links to
 * the web browser on Windows, so folders are opened with explorer / open / xdg-open instead.
 */
final class FolderOpener {

    private FolderOpener() {
    }

    static void open(Path folder) throws IOException {
        open(folder, System.getProperty("os.name", ""), UpdateInstaller.systemStarter());
    }

    static void open(Path folder, String osName, UpdateInstaller.ProcessStarter starter) throws IOException {
        Path absolute = folder.toAbsolutePath();
        Files.createDirectories(absolute); // e.g. the output folder before the first map is generated
        starter.start(command(absolute, osName));
    }

    static List<String> command(Path folder, String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        String program = os.startsWith("windows") ? "explorer.exe" : os.startsWith("mac") ? "open" : "xdg-open";
        return List.of(program, folder.toString());
    }
}
