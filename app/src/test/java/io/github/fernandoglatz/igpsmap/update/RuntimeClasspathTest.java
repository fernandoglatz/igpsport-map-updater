package io.github.fernandoglatz.igpsmap.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code mvn javafx:run} silently leaves out any jar the module system cannot read, such as kxml2 with
 * its invalid META-INF/services file; the app then starts but the map viewer fails. Guard every runtime
 * jar (target/runtime-classpath.txt, written by the build before the tests) and the app's own classes.
 */
class RuntimeClasspathTest {

    @TempDir
    Path dir;

    @Test
    void everyRuntimeJarCanBeReadAsAModule() throws IOException {
        List<Path> entries = new ArrayList<>();
        entries.add(Path.of("target/classes"));
        for (String entry : Files.readString(Path.of("target/runtime-classpath.txt")).strip().split(File.pathSeparator)) {
            entries.add(Path.of(entry));
        }
        assertFalse(entries.size() < 10, "runtime classpath looks incomplete: " + entries);

        List<String> unreadable = new ArrayList<>();
        for (Path entry : entries) {
            if (!readableAsModule(entry)) {
                unreadable.add(entry.getFileName().toString());
            }
        }
        assertEquals(List.of(), unreadable, "javafx:run would drop these from the classpath");
    }

    @Test
    void theCheckCatchesAServicesFileLikeKxml2s() throws IOException {
        // kxml2 2.3.0 lists two classes on one line, in the XML Pull style, which no module can provide.
        Path jar = dir.resolve("like-kxml2.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/org.xmlpull.v1.XmlPullParserFactory"));
            out.write("org.kxml2.io.KXmlParser,org.kxml2.io.KXmlSerializer\n".getBytes());
            out.closeEntry();
        }

        assertFalse(readableAsModule(jar));
        assertThrows(FindException.class, () -> ModuleFinder.of(jar).findAll());
    }

    private static boolean readableAsModule(Path entry) {
        try {
            ModuleFinder.of(entry).findAll();
            return true;
        } catch (FindException e) {
            return false;
        }
    }
}
