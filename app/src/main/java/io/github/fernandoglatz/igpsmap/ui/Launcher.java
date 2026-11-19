package io.github.fernandoglatz.igpsmap.ui;

import javafx.application.Application;

/** Plain main class so JavaFX can start from the classpath (non-modular) and from jpackage. */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(MainApp.class, args);
    }
}
