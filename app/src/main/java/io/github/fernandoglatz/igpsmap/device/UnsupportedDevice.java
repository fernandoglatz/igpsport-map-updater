package io.github.fernandoglatz.igpsmap.device;

import java.nio.file.Path;

/** A mounted iGPSPORT device that has no maps this app can update, with a message saying why. */
public record UnsupportedDevice(Path root, String message) {
}
