package io.github.fernandoglatz.igpsmap.domain;

/** Geographic bounding box in decimal degrees (WGS84). */
public record BoundingBox(double minLat, double minLon, double maxLat, double maxLon) {
}
