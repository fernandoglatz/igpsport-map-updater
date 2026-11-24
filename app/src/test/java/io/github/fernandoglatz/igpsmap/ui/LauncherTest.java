package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class LauncherTest {

    @Test
    void awtRunsHeadlessSoTheMapRendererNeverStartsItsOwnWindowSystem() {
        Properties props = new Properties();

        Launcher.preferHeadlessAwt(props);

        assertEquals("true", props.getProperty("java.awt.headless"));
    }

    @Test
    void anExplicitSettingIsKept() {
        Properties props = new Properties();
        props.setProperty("java.awt.headless", "false");

        Launcher.preferHeadlessAwt(props);

        assertEquals("false", props.getProperty("java.awt.headless"));
    }
}
