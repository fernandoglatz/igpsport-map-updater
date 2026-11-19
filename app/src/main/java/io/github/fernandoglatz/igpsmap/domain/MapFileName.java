package io.github.fernandoglatz.igpsmap.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** IGPSPORT map file name: {@code CC RRRR YYMMDD GEOCODE .map}. */
public record MapFileName(String countryCode, String regionCode, String date, String geoCode) {

    private static final Pattern PATTERN = Pattern.compile("^([A-Z]{2})(\\d{4})(\\d{6})([0-9A-Z]{12})\\.map$");

    public static Optional<MapFileName> parse(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(fileName);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new MapFileName(m.group(1), m.group(2), m.group(3), m.group(4)));
    }

    /** Identity of the region regardless of the map date. */
    public String regionKey() {
        return countryCode + regionCode + geoCode;
    }

    /** The YYMMDD part as a date (20YY), or empty when it isn't a real date. */
    public Optional<java.time.LocalDate> mapDate() {
        try {
            return Optional.of(java.time.LocalDate.parse("20" + date, java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public String toFileName() {
        return countryCode + regionCode + date + geoCode + ".map";
    }
}
