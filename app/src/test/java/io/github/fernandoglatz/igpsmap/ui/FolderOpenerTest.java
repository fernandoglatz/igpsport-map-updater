package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderOpenerTest {

    @TempDir
    Path dir;

    @Test
    void usesTheFileManagerOfEachSystemNotTheBrowser() {
        Path folder = Path.of("/data/output");
        assertEquals(List.of("explorer.exe", folder.toString()), FolderOpener.command(folder, "Windows 11"));
        assertEquals(List.of("open", folder.toString()), FolderOpener.command(folder, "Mac OS X"));
        assertEquals(List.of("xdg-open", folder.toString()), FolderOpener.command(folder, "Linux"));
    }

    @Test
    void createsAMissingFolderBeforeOpeningIt() throws IOException {
        List<List<String>> started = new ArrayList<>();
        Path output = dir.resolve("output");
        FolderOpener.open(output, "Linux", command -> {
            started.add(command);
            return null;
        });
        assertTrue(Files.isDirectory(output));
        assertEquals(List.of(List.of("xdg-open", output.toAbsolutePath().toString())), started);
    }
}
