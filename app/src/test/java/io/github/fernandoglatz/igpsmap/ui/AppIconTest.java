package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AppIconTest {

    @Test
    void everyWindowIconSizeIsBundled() throws IOException {
        assertEquals(7, AppIcon.SIZES.size());
        for (int size : AppIcon.SIZES) {
            try (InputStream in = AppIcon.class.getResourceAsStream(AppIcon.resource(size))) {
                assertNotNull(in, AppIcon.resource(size));
                BufferedImage image = ImageIO.read(in);
                assertEquals(size, image.getWidth(), AppIcon.resource(size));
                assertEquals(size, image.getHeight(), AppIcon.resource(size));
                assertTrue(image.getColorModel().hasAlpha(), "rounded corners need transparency");
            }
        }
    }

    @Test
    void installerIconsExist() throws IOException {
        // jpackage --icon: .png on Linux, .ico on Windows, .icns on macOS (see package.sh / package.ps1).
        Path packaging = Path.of("packaging");
        for (String file : new String[] {"icon.png", "icon.ico", "icon.icns"}) {
            assertTrue(Files.size(packaging.resolve(file)) > 1000, file);
        }
    }

    @Test
    void resourceNamesFollowTheGeneratorScript() {
        assertEquals("/ui/icon-32.png", AppIcon.resource(32));
    }
}
