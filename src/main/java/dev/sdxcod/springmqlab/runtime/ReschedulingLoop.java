package dev.sdxcod.springmqlab.runtime;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Fixed delay after completion; wake requests coalesce and never overlap the same loop.
 */
public final class ReschedulingLoop implements AutoCloseable {

    private final ScheduledExecutorService scheduler;
    private final LongSupplier task;
    private ScheduledFuture<?> next;
    private boolean running, wakeRequested, closed;
    private long generation;

    public ReschedulingLoop(ScheduledExecutorService scheduler, LongSupplier task) {
        this.scheduler = scheduler;
        this.task = task;
    }

    public synchronized void wake() {
        if (closed)
            return;
        if (running) {
            wakeRequested = true;
            return;
        }

        if (next != null)
            next.cancel(false);
        schedule(0);
    }

    private void schedule(long delay) {
        long ticket = ++generation;
        next = scheduler.schedule(() -> run(ticket), delay, TimeUnit.MILLISECONDS);
    }

    private void run(long ticket) {
        synchronized (this) {
            if (closed || ticket != generation)
                return;
            running = true;
            next = null;
        }
        long delay = 1000;
        try {
            delay = Math.max(10, task.getAsLong());
        } catch (RuntimeException e) {
            System.err.println("Scheduler task failed: " + e.getClass().getSimpleName());
        } finally {
            synchronized (this) {
                running = false;
                if (!closed)
                    schedule(wakeRequested ? 0 : delay);
                wakeRequested = false;
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        generation++;
        if (next != null)
            next.cancel(false);
    }
}
