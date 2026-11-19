package io.github.fernandoglatz.igpsmap.io;

import java.io.IOException;

/** Thrown when a file is not a readable Mapsforge map. */
public class InvalidMapFileException extends IOException {

    public InvalidMapFileException(String message) {
        super(message);
    }
}
