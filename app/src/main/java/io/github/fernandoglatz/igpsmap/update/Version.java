package io.github.fernandoglatz.igpsmap.update;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A release version like 1.2.3 (tags carry a "v" prefix). */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    /** System property that overrides the running version (development and manual testing). */
    public static final String OVERRIDE_PROPERTY = "igpsmap.version";

    private static final Pattern PATTERN = Pattern.compile("^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$");
    private static final Comparator<Version> ORDER = Comparator.comparingInt(Version::major)
            .thenComparingInt(Version::minor).thenComparingInt(Version::patch);

    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Version(Integer.parseInt(m.group(1)), number(m.group(2)), number(m.group(3))));
    }

    /** The running app's version: the override property, else the jar manifest; empty when run from source. */
    public static Optional<Version> current() {
        return current(System.getProperty(OVERRIDE_PROPERTY), Version.class.getPackage().getImplementationVersion());
    }

    static Optional<Version> current(String override, String manifest) {
        return override != null ? parse(override) : parse(manifest);
    }

    @Override
    public int compareTo(Version other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    private static int number(String digits) {
        return digits == null ? 0 : Integer.parseInt(digits);
    }
}
