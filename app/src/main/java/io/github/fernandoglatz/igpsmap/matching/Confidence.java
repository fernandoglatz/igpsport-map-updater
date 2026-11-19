package io.github.fernandoglatz.igpsmap.matching;

/** How sure the matcher is about a row; drives the badge and the default checkbox state. */
public enum Confidence {
    CSV, MANUAL, HIGH, MEDIUM, LOW, FALLBACK, UNMATCHED;

    public static Confidence fromScore(double score) {
        if (score >= 0.9) {
            return HIGH;
        }
        return score >= 0.6 ? MEDIUM : LOW;
    }

    public boolean preselected() {
        return this == CSV || this == MANUAL || this == HIGH;
    }
}
