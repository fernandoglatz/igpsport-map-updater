package io.github.fernandoglatz.igpsmap.ui;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.image.Image;

/** The application icon in every size the window and taskbar may ask for (made by app/packaging/make_icon.py). */
final class AppIcon {

    static final List<Integer> SIZES = List.of(16, 24, 32, 48, 64, 128, 256);

    private AppIcon() {
    }

    static String resource(int size) {
        return "/ui/icon-" + size + ".png";
    }

    /** Loaded images; sizes whose resource is missing are skipped. Needs the JavaFX toolkit. */
    static List<Image> images() {
        List<Image> images = new ArrayList<>();
        for (int size : SIZES) {
            InputStream in = AppIcon.class.getResourceAsStream(resource(size));
            if (in != null) {
                images.add(new Image(in));
            }
        }
        return images;
    }
}
