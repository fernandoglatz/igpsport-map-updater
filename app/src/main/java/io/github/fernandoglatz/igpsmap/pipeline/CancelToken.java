package io.github.fernandoglatz.igpsmap.pipeline;

/**
 * Thread-safe cancellation flag shared between the UI and a running generation. A bound worker
 * thread is interrupted on cancel, so blocking calls (e.g. an HTTP request waiting for a stalled
 * server) stop right away instead of at their timeout.
 */
public final class CancelToken {

    private volatile boolean cancelled;
    private volatile Thread worker;

    public void cancel() {
        cancelled = true;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    void bind(Thread thread) {
        worker = thread;
        if (cancelled) {
            thread.interrupt();
        }
    }

    void unbind() {
        worker = null;
    }
}
