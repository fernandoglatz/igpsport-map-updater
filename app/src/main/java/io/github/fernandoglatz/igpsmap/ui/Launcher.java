package io.github.fernandoglatz.igpsmap.ui;

import java.util.Properties;
import javafx.application.Application;

/** Plain main class so JavaFX can start from the classpath (non-modular) and from jpackage. */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        preferHeadlessAwt(System.getProperties());
        Application.launch(MainApp.class, args);
    }

    /**
     * The map viewer renders with AWT images but never opens AWT windows; headless keeps AWT from
     * starting its own window system next to JavaFX (which clashes on macOS). An explicit -D wins.
     */
    static void preferHeadlessAwt(Properties props) {
        if (props.getProperty("java.awt.headless") == null) {
            props.setProperty("java.awt.headless", "true");
        }
    }
}
