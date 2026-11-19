package io.github.fernandoglatz.igpsmap.device;

import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Finds IGPSPORT map files on mounted drives or inside a chosen folder. */
public final class DeviceScanner {

    public static final int DEFAULT_DEPTH = 4;
    private static final Set<String> SKIPPED_DIRS = Set.of(
            "System Volume Information", "$RECYCLE.BIN", "RECYCLER", "lost+found");

    private final String osName;
    private final String userName;
    private final Supplier<List<Path>> fsRoots;
    private final Path base;

    DeviceScanner(String osName, String userName, Supplier<List<Path>> fsRoots, Path base) {
        this.osName = osName;
        this.userName = userName;
        this.fsRoots = fsRoots;
        this.base = base;
    }

    public static DeviceScanner system() {
        return new DeviceScanner(
                System.getProperty("os.name", ""),
                System.getProperty("user.name", ""),
                () -> Arrays.stream(File.listRoots()).map(File::toPath).toList(),
                Path.of("/"));
    }

    /** Candidate mount points for removable devices on this OS. */
    public List<Path> mountRoots() {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            String systemDrive = Objects.requireNonNullElse(System.getenv("SystemDrive"), "C:").toUpperCase(Locale.ROOT);
            return fsRoots.get().stream()
                    .filter(p -> !p.toString().toUpperCase(Locale.ROOT).startsWith(systemDrive))
                    .toList();
        }
        if (os.startsWith("mac")) {
            return children(base.resolve("Volumes"));
        }
        List<Path> roots = new ArrayList<>(children(base.resolve("media").resolve(userName)));
        roots.addAll(children(base.resolve("run/media").resolve(userName)));
        return roots;
    }

    /** Recursively finds IGPSPORT map files below {@code root}, sorted by file name. */
    public List<DetectedMap> scan(Path root, int maxDepth) {
        List<DetectedMap> found = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return found;
        }
        try {
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), maxDepth + 1,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(root)) {
                                return FileVisitResult.CONTINUE;
                            }
                            String name = String.valueOf(dir.getFileName());
                            // Backup folders hold old copies of the maps, not the ones in use.
                            return name.startsWith(".") || SKIPPED_DIRS.contains(name)
                                    || name.equalsIgnoreCase("backup")
                                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile()) {
                                Optional<MapFileName> name = MapFileName.parse(file.getFileName().toString());
                                name.ifPresent(n -> found.add(new DetectedMap(file, n)));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            // Unreadable root: report what was found so far.
        }
        // A device can hold the same region twice (e.g. originals in Backup/ and regenerated maps
        // in iGPSPORT/Maps/). Keep one file per region: the newest date wins.
        java.util.Map<String, DetectedMap> byRegion = new java.util.TreeMap<>();
        for (DetectedMap map : found) {
            byRegion.merge(map.name().regionKey(), map,
                    (a, b) -> a.name().date().compareTo(b.name().date()) >= 0 ? a : b);
        }
        List<DetectedMap> unique = new ArrayList<>(byRegion.values());
        unique.sort(Comparator.comparing(m -> m.path().getFileName().toString()));
        return unique;
    }

    /** Mounted devices that contain at least one IGPSPORT map file. */
    public List<DetectedSource> detectDevices() {
        List<DetectedSource> sources = new ArrayList<>();
        for (Path root : mountRoots()) {
            List<DetectedMap> maps = scan(root, DEFAULT_DEPTH);
            if (!maps.isEmpty()) {
                sources.add(new DetectedSource(label(root, maps.size()), root, maps));
            }
        }
        return sources;
    }

    public static String label(Path root, int count) {
        Path name = root.getFileName();
        return (name != null ? name.toString() : root.toString()) + " — " + count + (count == 1 ? " map" : " maps");
    }

    private static List<Path> children(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
