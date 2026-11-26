package io.github.fernandoglatz.igpsmap.io;

import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The device checksum list, calculated from final map bytes and replaced atomically. */
public final class MapMd5List {
    public static final String FILE_NAME = "map_md5_list.cfg";
    private static final Pattern VERSION = Pattern.compile("^MAP_VER:(\\d+)$");

    private MapMd5List() { }

    public static Path write(Path output, List<Path> references, BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(output);
        Path target = output.resolve(FILE_NAME);
        String version = version(target);
        if (version == null) {
            for (Path reference : references) {
                if ((version = version(reference)) != null) break;
            }
        }
        // MAP_VER is the device list format/version, not the OpenStreetMap date.
        if (version == null) version = "130";
        List<Path> maps;
        try (Stream<Path> files = Files.list(output)) {
            maps = files.filter(Files::isRegularFile)
                    .filter(p -> MapFileName.parse(p.getFileName().toString()).isPresent())
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        StringBuilder text = new StringBuilder("MAP_NUM:" + maps.size() + "\r\nMAP_VER:" + version + "\r\n");
        for (Path map : maps) {
            checkCancel(cancelled);
            String filename = map.getFileName().toString();
            text.append(filename, 0, filename.length() - 4).append(':')
                    .append(md5(map, cancelled)).append("\r\n");
        }
        Path temporary = Files.createTempFile(output, ".map-md5-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.US_ASCII);
            checkCancel(cancelled);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    private static String version(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            var match = VERSION.matcher(line.strip());
            if (match.matches()) return match.group(1);
        }
        return null;
    }

    private static String md5(Path file, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) >= 0;) {
                checkCancel(cancelled);
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void checkCancel(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("MD5 update cancelled");
    }
}
