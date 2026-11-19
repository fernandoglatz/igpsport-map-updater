package io.github.fernandoglatz.igpsmap.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** {@link ProcessRunner} backed by {@link ProcessBuilder}; cancelling kills the process tree. */
public final class SystemProcessRunner implements ProcessRunner {

    /** Every process started by any runner; killed on {@link #killAll()} and at JVM shutdown. */
    private static final Set<Process> ACTIVE = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(SystemProcessRunner::destroyActive, "kill-child-processes"));
    }

    /** Kills every running child process and its descendants (used when the app closes). */
    public void killAll() {
        destroyActive();
    }

    private static void destroyActive() {
        for (Process process : ACTIVE) {
            destroyTree(process);
        }
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    @Override
    public int run(List<String> command, Consumer<String> lines, CancelToken cancel)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ACTIVE.add(process);
        Thread watcher = new Thread(() -> {
            while (process.isAlive()) {
                if (cancel.isCancelled()) {
                    destroyTree(process);
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "process-cancel-watcher");
        watcher.setDaemon(true);
        watcher.start();
        boolean finished = false;
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.accept(line);
                }
            }
            int exit = process.waitFor();
            finished = true;
            return exit;
        } finally {
            if (!finished) {
                destroyTree(process); // never leave osmosis running after a failure here
            }
            ACTIVE.remove(process);
            watcher.interrupt();
        }
    }
}
