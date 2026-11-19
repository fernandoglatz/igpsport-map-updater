package io.github.fernandoglatz.igpsmap.ui;

import io.github.fernandoglatz.igpsmap.device.DetectedMap;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The full path of the folder(s) the maps come from, e.g. "/media/x/IGS630S/iGPSPORT/Maps · 265 maps". */
final class SourceFolders {

    private SourceFolders() {
    }

    static String describe(Path root, List<DetectedMap> maps) {
        if (maps.isEmpty()) {
            return root.toAbsolutePath() + " · no IGPSPORT maps found";
        }
        Map<Path, Long> counts = maps.stream().collect(Collectors.groupingBy(
                m -> m.path().toAbsolutePath().getParent(), LinkedHashMap::new, Collectors.counting()));
        if (counts.size() == 1) {
            Map.Entry<Path, Long> only = counts.entrySet().iterator().next();
            return only.getKey() + " · " + count(only.getValue());
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Path, Long>comparingByValue(Comparator.reverseOrder()))
                .map(e -> e.getKey() + " (" + count(e.getValue()) + ")")
                .collect(Collectors.joining(", "));
    }

    private static String count(long n) {
        return n + (n == 1 ? " map" : " maps");
    }
}
