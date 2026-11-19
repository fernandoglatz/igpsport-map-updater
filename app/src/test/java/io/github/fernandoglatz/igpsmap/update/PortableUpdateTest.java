package io.github.fernandoglatz.igpsmap.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortableUpdateTest {

    private static final String NAME = "IGPSPORT Map Updater";
    private static final Platform LINUX = new Platform("linux", "x64");
    private static final Platform WINDOWS = new Platform("windows", "x64");
    private static final Platform MAC = new Platform("macos", "arm64");

    @TempDir
    Path dir;

    @Test
    void locatesThePortableFolderFromTheLauncher() {
        Path linux = dir.resolve("apps/igpsport");
        assertEquals(Optional.of(new PortableApp(linux, linux.resolve("bin/" + NAME), LINUX)),
                PortableApp.locate(linux.resolve("bin/" + NAME).toString(), LINUX));
        Path windows = dir.resolve("IGPSPORT");
        assertEquals(windows, PortableApp.locate(windows.resolve(NAME + ".exe").toString(), WINDOWS).orElseThrow().root());
        Path mac = dir.resolve(NAME + ".app");
        assertEquals(mac, PortableApp.locate(mac.resolve("Contents/MacOS/" + NAME).toString(), MAC).orElseThrow().root());
    }

    @Test
    void unexpectedLayoutsAreNotUpdatedSilently() {
        assertTrue(PortableApp.locate(null, LINUX).isEmpty());
        assertTrue(PortableApp.locate("", LINUX).isEmpty());
        assertTrue(PortableApp.locate(dir.resolve("x/" + NAME).toString(), LINUX).isEmpty()); // not in bin/
        assertTrue(PortableApp.locate(dir.resolve("x/" + NAME).toString(), MAC).isEmpty());
    }

    private Path zip(String... entries) throws IOException {
        Path file = dir.resolve("update.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.write(entry.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    @Test
    void extractsZipArchives() throws IOException {
        Path archive = zip(NAME + "/" + NAME + ".exe", NAME + "/app/a.jar");
        Path staging = dir.resolve("staging");
        PortableUpdate.extract(archive, staging);
        assertEquals(NAME + "/app/a.jar", Files.readString(staging.resolve(NAME + "/app/a.jar")));
    }

    @Test
    void zipEntriesCannotEscapeTheStagingFolder() throws IOException {
        Path archive = zip(NAME + "/ok.txt", "../evil.txt");
        IOException e = assertThrows(IOException.class, () -> PortableUpdate.extract(archive, dir.resolve("staging")));
        assertTrue(e.getMessage().contains("outside"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("evil.txt")));
    }

    @Test
    void extractsTarGzArchivesKeepingPermissions() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/tar")) || Files.isExecutable(Path.of("/usr/bin/tar")));
        Path image = dir.resolve("image");
        Path launcher = Files.createDirectories(image.resolve(NAME + "/bin")).resolve(NAME);
        Files.writeString(launcher, "#!/bin/sh\n");
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path archive = dir.resolve("update.tar.gz");
        // Same layout as app/package.sh: tar -C <dist> -czf <file> .
        Process tar = new ProcessBuilder("tar", "-C", image.toString(), "-czf", archive.toString(), ".").start();
        assertEquals(0, tar.waitFor());

        Path staging = dir.resolve("staging");
        PortableUpdate.extract(archive, staging);

        assertTrue(Files.isExecutable(staging.resolve(NAME + "/bin/" + NAME)));
    }

    @Test
    void findsTheNewAppFolderEvenWhenTheOldOneWasRenamed() throws IOException {
        Path staging = dir.resolve("staging");
        Files.createDirectories(staging.resolve(NAME + "/bin"));
        Files.writeString(staging.resolve(NAME + "/bin/" + NAME), "x");
        assertEquals(Optional.of(staging.resolve(NAME)), PortableUpdate.findNewRoot(staging, Path.of("bin", NAME)));
        assertTrue(PortableUpdate.findNewRoot(staging, Path.of("bin", "other")).isEmpty());
    }

    @Test
    void stagingFolderSitsNextToTheAppSoTheSwapIsARename() {
        PortableApp app = new PortableApp(dir.resolve("apps/igpsport"), dir.resolve("apps/igpsport/bin/x"), LINUX);
        assertEquals(dir.resolve("apps/.igpsport.update"), PortableUpdate.stagingDir(app));
    }

    @Test
    void buildsTheHelperCommandForEachPlatform() {
        PortableApp linux = new PortableApp(Path.of("/a/app"), Path.of("/a/app/bin/x"), LINUX);
        assertEquals(List.of("nohup", "/bin/sh", "/u/replace-app.sh", "42", "/a/app", "/a/.app.update/x", "/a/app/bin/x",
                        "/a/.app.update"),
                PortableUpdate.helperCommand(linux, Path.of("/u/replace-app.sh"), 42, Path.of("/a/.app.update/x"),
                        Path.of("/a/.app.update")));
        PortableApp windows = new PortableApp(Path.of("C:/apps/app"), Path.of("C:/apps/app/x.exe"), WINDOWS);
        List<String> command = PortableUpdate.helperCommand(windows, Path.of("C:/u/replace-app.ps1"), 42,
                Path.of("C:/apps/.app.update/app"), Path.of("C:/apps/.app.update"));
        assertEquals(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File"),
                command.subList(0, 7));
        assertTrue(command.containsAll(List.of("-ProcessId", "42", "-Old", "-New", "-Launcher", "-Staging")), command.toString());
    }

    @Test
    void helperScriptsAreBundled() throws IOException {
        Path updates = dir.resolve("updates");
        assertTrue(Files.readString(PortableUpdate.writeHelper(updates, LINUX)).startsWith("#!/bin/sh"));
        assertTrue(Files.readString(PortableUpdate.writeHelper(updates, WINDOWS)).contains("Wait for the app"));
    }

    /** Runs the real shell helper: it waits for a process to end, swaps the folders and starts the launcher. */
    @Test
    void shellHelperSwapsTheFoldersAndRestartsTheApp() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        Path apps = Files.createDirectories(dir.resolve("apps"));
        Path old = Files.createDirectories(apps.resolve("app/bin"));
        Files.writeString(old.resolve("version.txt"), "old");
        Path staging = Files.createDirectories(apps.resolve(".app.update"));
        Path fresh = Files.createDirectories(staging.resolve(NAME + "/bin"));
        Files.writeString(fresh.resolve("version.txt"), "new");
        Path marker = dir.resolve("started.txt");
        Path launcher = fresh.resolve("launcher");
        Files.writeString(launcher, "#!/bin/sh\necho started > '" + marker + "'\n");
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));

        Process app = new ProcessBuilder("sleep", "1").start(); // stands in for the running app
        PortableApp portable = new PortableApp(apps.resolve("app"), apps.resolve("app/bin/launcher"), LINUX);
        Path script = PortableUpdate.writeHelper(dir.resolve("updates"), LINUX);
        Process helper = new ProcessBuilder(PortableUpdate.helperCommand(portable, script, app.pid(),
                staging.resolve(NAME), staging)).redirectErrorStream(true).redirectOutput(dir.resolve("helper.log").toFile())
                .start();

        assertTrue(helper.waitFor(20, TimeUnit.SECONDS), "helper did not finish");
        assertEquals(0, helper.exitValue(), Files.readString(dir.resolve("helper.log")));
        for (int i = 0; i < 50 && !Files.exists(marker); i++) {
            Thread.sleep(100);
        }
        assertEquals("new", Files.readString(apps.resolve("app/bin/version.txt")));
        assertTrue(Files.exists(marker), "the new version was not started");
        assertFalse(Files.exists(staging), "staging folder left behind");
        try (var left = Files.list(apps)) {
            assertEquals(List.of(apps.resolve("app")), left.toList(), "backup of the old version left behind");
        }
    }

    @Test
    void shellHelperGivesUpWhenTheAppNeverExits() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        Path apps = Files.createDirectories(dir.resolve("apps"));
        Files.createDirectories(apps.resolve("app"));
        Process app = new ProcessBuilder("sleep", "30").start();
        try {
            PortableApp portable = new PortableApp(apps.resolve("app"), apps.resolve("app/bin/launcher"), LINUX);
            Path script = PortableUpdate.writeHelper(dir.resolve("updates"), LINUX);
            List<String> command = new java.util.ArrayList<>(PortableUpdate.helperCommand(portable, script, app.pid(),
                    apps.resolve("new"), apps.resolve(".app.update")));
            Process helper = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(dir.resolve("helper.log").toFile())
                    .start();
            // The helper waits up to its timeout; the test only checks it doesn't touch the app meanwhile.
            assertFalse(helper.waitFor(1, TimeUnit.SECONDS));
            assertTrue(Files.isDirectory(apps.resolve("app")));
            helper.destroy();
        } finally {
            app.destroy();
        }
    }

    @Test
    void writesNothingWhenTheArchiveIsUnreadable() throws IOException {
        Path broken = Files.write(dir.resolve("broken.zip"), new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> PortableUpdate.extract(broken, dir.resolve("staging")));
    }
}
