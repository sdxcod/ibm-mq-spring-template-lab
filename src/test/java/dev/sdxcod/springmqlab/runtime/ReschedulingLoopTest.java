package dev.sdxcod.springmqlab.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReschedulingLoopTest {
    @Test
    void wakeDuringExecutionCoalescesAndCloseCancelsFutureRuns() throws Exception {
        var executor = Executors.newSingleThreadScheduledExecutor();
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var loop = new ReschedulingLoop(executor, () -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else second.countDown();
            return 60000;
        });
        try {
            loop.wake();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            loop.wake();
            loop.wake();
            release.countDown();
            assertTrue(second.await(2, TimeUnit.SECONDS));
            loop.close();
            assertEquals(2, calls.get());
        } finally {
            loop.close();
            executor.shutdownNow();
        }
    }
}
