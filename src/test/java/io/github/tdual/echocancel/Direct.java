package io.github.tdual.echocancel;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs tasks at once on the calling thread, so tests are repeatable. */
final class Direct extends AbstractExecutorService {
    private boolean shut;
    public void execute(Runnable r) { r.run(); }
    public void shutdown() { shut = true; }
    public List<Runnable> shutdownNow() { shut = true; return List.of(); }
    public boolean isShutdown() { return shut; }
    public boolean isTerminated() { return shut; }
    public boolean awaitTermination(long t, TimeUnit u) { return true; }
}
