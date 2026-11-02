package com.atakmap.android.tfr.plugin.engine;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** The real scheduler: one background thread, wall-clock time. */
public final class ExecutorScheduler implements Scheduler {

    private final ScheduledExecutorService exec = Executors
            .newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "TFR-TAK engine");
                    t.setDaemon(true);
                    return t;
                }
            });

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    @Override
    public void execute(Runnable r) {
        try {
            exec.execute(r);
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    @Override
    public void schedule(Runnable r, long delayMs) {
        try {
            exec.schedule(r, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    @Override
    public void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void shutdown() {
        exec.shutdownNow();
    }
}
