package dev.sdxcod.springmqlab.runtime;

import dev.sdxcod.springmqlab.config.LabProperties;
import dev.sdxcod.springmqlab.config.QueueSettingsClient;
import dev.sdxcod.springmqlab.config.SettingsRegistry;
import dev.sdxcod.springmqlab.storage.FileOutbox;
import dev.sdxcod.springmqlab.transport.MqOperationsFactory;
import dev.sdxcod.springmqlab.transport.ReceivedMessage;
import dev.sdxcod.springmqlab.transport.SendFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class AgentRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private final LabProperties properties;
    private final SettingsRegistry registry;
    private final QueueSettingsClient client;
    private final MqOperationsFactory factory;
    private final FileOutbox outbox;
    private final Consumer<ReceivedMessage> handler;
    private final ScheduledExecutorService workers = scheduler("mq-cycles");
    private final ScheduledExecutorService poller = scheduler("settings-poller");
    private final ReschedulingLoop inward, outward, settings;
    private volatile boolean closed, started;

    private static ScheduledExecutorService scheduler(String name) {
        var executor = new ScheduledThreadPoolExecutor(1, r -> {
            var t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public AgentRuntime(LabProperties properties, SettingsRegistry registry, QueueSettingsClient client,
                        MqOperationsFactory factory, FileOutbox outbox, Consumer<ReceivedMessage> handler) {
        this.properties = properties;
        this.registry = registry;
        this.client = client;
        this.factory = factory;
        this.outbox = outbox;
        this.handler = handler;
        inward = new ReschedulingLoop(workers, this::runInward);
        outward = new ReschedulingLoop(workers, this::runOutward);
        settings = new ReschedulingLoop(poller, this::pollSettings);
    }

    public synchronized void start() {
        if (closed || started)
            throw new IllegalStateException("Runtime already started or closed");
        started = true;
        settings.wake();
        inward.wake();
        outward.wake();
    }

    public long pollSettings() {
        try {
            boolean changed = registry.accept(client.fetch());
            if (changed) {
                log.info("Applied QueueSettings revision={} enabled={}",
                        registry.current().settings().revision(), registry.current().settings().enabled());
                if (started) {
                    inward.wake();
                    outward.wake();
                }
            }

            return properties.settingsPollMs();
        } catch (Exception e) {
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            log.warn("QueueSettings fetch failed: {}; existing settings expire after {}ms",
                    e.getClass().getSimpleName(), properties.settingsMaxAgeMs());

            return properties.settingsFailureMs();
        }
    }

    public long runInward() {
        var snapshot = registry.forWork();

        if (closed || snapshot == null)
            return properties.idleDelayMs();

        var s = snapshot.settings();
        int count = 0;
        try (var ops = factory.open(s)) {
            while (count < s.inwardBatchCount() && !closed && registry.permits(snapshot)) {
                if (ops.receive(s.inwardQueue(), null, handler, false).isEmpty())
                    break;
                count++;
            }
            log.info("Inward cycle revision={} committed={} maximum={}", s.revision(), count, s.inwardBatchCount());

            return s.inwardIntervalMs();
        } catch (Exception e) {
            log.warn("Inward failed after {} committed messages: {}", count, e.getClass().getSimpleName());
            return s.failureRetryMs();
        }
    }

    public long runOutward() {
        var snapshot = registry.forWork();

        if (closed || snapshot == null)
            return properties.idleDelayMs();
        var s = snapshot.settings();
        int count = 0;

        try (var ops = factory.open(s)) {
            while (count < s.outwardBatchCount() && !closed && registry.permits(snapshot)) {
                var claim = outbox.claim();
                if (claim.isEmpty()) break;
                var work = claim.get();
                try {
                    var result = ops.send(s.outwardQueue(), work.envelope(), false);
                    if (!result.committed())
                        throw new SendFailure(SendFailure.Kind.RETRYABLE, new IllegalStateException("Send rolled back"));
                    outbox.sent(work, result.messageId());
                    count++;
                } catch (SendFailure failure) {
                    if (failure.kind() == SendFailure.Kind.RETRYABLE)
                        outbox.retry(work);
                    else
                        outbox.uncertain(work, "MQ transaction outcome unknown");

                    throw failure;
                } catch (Exception failure) {
                    outbox.uncertain(work, "Send or local result recording failed; inspect before replay");
                    throw failure;
                }
            }
            log.info("Outward cycle revision={} committed={} maximum={}", s.revision(), count, s.outwardBatchCount());
            return s.outwardIntervalMs();
        } catch (Exception e) {
            log.warn("Outward failed after {} completed work items: {}", count, e.getClass().getSimpleName());
            return s.failureRetryMs();
        }
    }

    @Override
    public void close() {
        closed = true;
        settings.close();
        inward.close();
        outward.close();
        poller.shutdown();
        workers.shutdown();

        for (var executor : new ExecutorService[]{poller, workers}) {
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
