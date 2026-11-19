package io.github.fernandoglatz.igpsmap.ui;

import java.io.File;
import java.nio.file.Path;

/** Shows paths under the home folder as "~/..." and accepts "~" when typed. */
final class PathText {

    private PathText() {
    }

    static String display(Path path, Path home) {
        Path p = path.toAbsolutePath().normalize();
        Path h = home.toAbsolutePath().normalize();
        if (p.equals(h)) {
            return "~";
        }
        return p.startsWith(h) ? "~" + File.separator + h.relativize(p) : p.toString();
    }

    static Path parse(String text, Path home) {
        String t = text.strip();
        if (t.equals("~")) {
            return home;
        }
        if (t.startsWith("~/") || t.startsWith("~\\")) {
            return home.resolve(t.substring(2));
        }
        return Path.of(t);
    }

    static Path home() {
        return Path.of(System.getProperty("user.home"));
    }
}
