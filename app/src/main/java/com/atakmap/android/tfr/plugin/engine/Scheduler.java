package com.atakmap.android.tfr.plugin.engine;

/**
 * Runs tasks one at a time on the engine's thread, now or after a delay.
 * Real: a single-thread executor. Tests: a virtual-time manual scheduler.
 */
public interface Scheduler {

    /** Current time, epoch ms. */
    long now();

    void execute(Runnable r);

    void schedule(Runnable r, long delayMs);

    /** Pause the engine thread (between FAA requests). */
    void pause(long ms);

    void shutdown();
}
