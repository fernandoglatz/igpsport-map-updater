package io.github.fernandoglatz.igpsmap.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapsCsvTest {

    private static final String HEADER = "Original filename,OSM BPF URL,Poly URL";
    private static final String PBF = "https://download.geofabrik.de/south-america/brazil-latest.osm.pbf";
    private static final String DF = "https://download.openstreetmap.fr/polygons/south-america/brazil/central-west/distrito-federal.poly";
    private static final String GO = "https://download.openstreetmap.fr/polygons/south-america/brazil/central-west/goias.poly";

    @TempDir
    Path dir;

    private Path csv(String... lines) throws IOException {
        Path file = dir.resolve("maps.csv");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void readsRowsSkippingHeaderAndBlankLines() throws IOException {
        Path file = csv(HEADER,
                "BR01002303102B83FO00N00E.map," + PBF + "," + DF,
                "",
                "BR02002303102833DN04O04Q.map," + PBF + "," + GO + "  ",
                "");
        List<MapsCsv.Row> rows = MapsCsv.read(file);
        assertEquals(2, rows.size());
        assertEquals(new MapsCsv.Row("BR02002303102833DN04O04Q.map", PBF, GO), rows.get(1));
    }

    @Test
    void missingFileReadsAsEmpty() throws IOException {
        assertTrue(MapsCsv.read(dir.resolve("nope.csv")).isEmpty());
    }

    @Test
    void findsRowByRegionKeyIgnoringDate() throws IOException {
        List<MapsCsv.Row> rows = MapsCsv.read(csv(HEADER, "BR01002303102B83FO00N00E.map," + PBF + "," + DF));
        assertEquals(DF, MapsCsv.findByRegionKey(rows, "BR01002B83FO00N00E").orElseThrow().polyUrl());
        assertTrue(MapsCsv.findByRegionKey(rows, "BR02002833DN04O04Q").isEmpty());
    }

    @Test
    void upsertReplacesSameRegionInPlace() throws IOException {
        Path file = csv(HEADER,
                "BR01002303102B83FO00N00E.map," + PBF + "," + DF,
                "BR02002303102833DN04O04Q.map," + PBF + "," + GO);
        MapsCsv.upsert(file, List.of(new MapsCsv.Row("BR01002601122B83FO00N00E.map", PBF, "https://x/df.poly")));
        List<String> lines = Files.readAllLines(file);
        assertEquals(List.of(HEADER,
                "BR01002601122B83FO00N00E.map," + PBF + ",https://x/df.poly",
                "BR02002303102833DN04O04Q.map," + PBF + "," + GO), lines);
    }

    @Test
    void upsertAppendsNewRegionAndCreatesFileWithHeader() throws IOException {
        Path file = dir.resolve("new.csv");
        MapsCsv.upsert(file, List.of(new MapsCsv.Row("PT01002601120AB0CD00100F.map", "https://p", "https://q")));
        assertEquals(List.of(HEADER, "PT01002601120AB0CD00100F.map,https://p,https://q"), Files.readAllLines(file));
    }

    @Test
    void upsertPreservesRowsWithUnknownNames() throws IOException {
        Path file = csv(HEADER, "custom-name.map,https://a,https://b");
        MapsCsv.upsert(file, List.of(new MapsCsv.Row("BR01002303102B83FO00N00E.map", PBF, DF)));
        assertEquals(List.of(HEADER, "custom-name.map,https://a,https://b",
                "BR01002303102B83FO00N00E.map," + PBF + "," + DF), Files.readAllLines(file));
    }

    @Test
    void upsertLeavesNoTempFiles() throws IOException {
        Path file = csv(HEADER);
        MapsCsv.upsert(file, List.of(new MapsCsv.Row("BR01002303102B83FO00N00E.map", PBF, DF)));
        try (var files = Files.list(dir)) {
            assertEquals(List.of(file), files.toList());
        }
    }
}
