package io.github.fernandoglatz.igpsmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class SystemProcessRunnerTest {

    private final String java = OsmosisCommand.currentJavaExecutable().toString();

    @Test
    void streamsMergedOutputAndReturnsExitCode() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        int exit = new SystemProcessRunner().run(List.of(java, "-version"), lines::add, new CancelToken());
        assertEquals(0, exit);
        assertTrue(lines.stream().anyMatch(l -> l.contains("version")), lines.toString());
    }

    @Test
    void nonZeroExitIsReturned() throws Exception {
        int exit = new SystemProcessRunner().run(List.of(java, "-no-such-option"), l -> { }, new CancelToken());
        assertNotEquals(0, exit);
    }

    @Test
    void killAllStopsRunningProcesses() {
        assumeTrue(File.separatorChar == '/');
        SystemProcessRunner runner = new SystemProcessRunner();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                runner.killAll();
            }).start();
            int exit = runner.run(List.of("sleep", "30"), l -> { }, new CancelToken());
            assertNotEquals(0, exit);
        });
    }

    @Test
    void cancelKillsTheProcess() {
        assumeTrue(File.separatorChar == '/');
        CancelToken cancel = new CancelToken();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                cancel.cancel();
            }).start();
            int exit = new SystemProcessRunner().run(List.of("sleep", "30"), l -> { }, cancel);
            assertNotEquals(0, exit);
        });
    }
}
