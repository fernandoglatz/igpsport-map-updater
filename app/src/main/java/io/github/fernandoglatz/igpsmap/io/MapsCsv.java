package io.github.fernandoglatz.igpsmap.io;

import io.github.fernandoglatz.igpsmap.domain.MapFileName;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The 3-column {@code maps.csv} of confirmed matches. Rows are matched by region key
 * (country + region code + GEOCODE), ignoring the date in the file name.
 */
public final class MapsCsv {

    public static final String HEADER = "Original filename,OSM BPF URL,Poly URL";

    public record Row(String originalFileName, String pbfUrl, String polyUrl) {

        Optional<String> regionKey() {
            return MapFileName.parse(originalFileName).map(MapFileName::regionKey);
        }

        String toLine() {
            return originalFileName + "," + pbfUrl + "," + polyUrl;
        }
    }

    private MapsCsv() {
    }

    public static List<Row> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || (i == 0 && line.equals(HEADER))) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length < 3 || parts[0].isBlank()) {
                continue;
            }
            rows.add(new Row(parts[0].strip(), parts[1].strip(), parts[2].strip()));
        }
        return rows;
    }

    public static Optional<Row> findByRegionKey(List<Row> rows, String regionKey) {
        return rows.stream().filter(r -> r.regionKey().filter(regionKey::equals).isPresent()).findFirst();
    }

    /** Replaces rows with the same region key in place and appends new ones. Writes atomically. */
    public static void upsert(Path file, List<Row> confirmed) throws IOException {
        Map<String, Row> pending = new LinkedHashMap<>();
        for (Row row : confirmed) {
            pending.put(row.regionKey().orElse(row.originalFileName()), row);
        }
        List<String> out = new ArrayList<>();
        out.add(HEADER);
        for (Row existing : read(file)) {
            String key = existing.regionKey().orElse(existing.originalFileName());
            Row replacement = pending.remove(key);
            out.add((replacement != null ? replacement : existing).toLine());
        }
        pending.values().forEach(r -> out.add(r.toLine()));

        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".maps", ".csv.tmp");
        try {
            Files.writeString(tmp, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
