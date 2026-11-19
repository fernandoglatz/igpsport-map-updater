package io.github.fernandoglatz.igpsmap.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Runs an external command, streaming its merged output line by line. */
@FunctionalInterface
public interface ProcessRunner {

    int run(List<String> command, Consumer<String> lines, CancelToken cancel) throws IOException, InterruptedException;
}
