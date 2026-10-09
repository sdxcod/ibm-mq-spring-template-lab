package dev.sdxcod.springmqlab.transport;

import dev.sdxcod.springmqlab.config.LabProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt in with -Pmq-it. Reads only test-created IDs, never purges queues.
 */
class SpringMqIT {
    private final LabProperties properties = new LabProperties(
            "help",
            Path.of("target/it-data"),
            Path.of(env("LAB_MQ_PASSWORD_FILE", ".secrets/mqAppPassword")),
            env("LAB_MQ_PASSWORD", ""), env("LAB_MQ_USER", "app"),
            URI.create("http://localhost:8088/queue-settings"),
            1000, 1000, 15000, 2000, 500,
            "FAIL:",
            new LabProperties.LocalMq(env("LAB_MQ_HOST", "localhost"),
                    Integer.parseInt(env("LAB_MQ_PORT", "1414")),
                    env("LAB_MQ_QMGR", "QM1"), env("LAB_MQ_CHANNEL", "DEV.APP.SVRCONN"),
                    "DEV.LAB.IN", "DEV.LAB.OUT", env("LAB_MQ_TEST_QUEUE",
                    "DEV.LAB.TEST")));
    private final MqOperationsFactory factory = new IbmMqOperationsFactory(properties);
    private final List<String> own = new ArrayList<>();

    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }

    private MqOperations open() throws Exception {
        return factory.open(properties.localSettings());
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!own.isEmpty()) try (var ops = open()) {
            for (String id : own)
                ops.receive(properties.mq().testQueue(), id, m -> {
                }, false);
        }
    }

    @Test
    void persistentUtf8RoundtripCommitsAndRemoves() throws Exception {
        String q = properties.mq().testQueue(), text = "سلام Spring " + UUID.randomUUID();
        try (var ops = open()) {
            String id = ops.send(q, text, false).messageId();
            own.add(id);
            var received = ops.receive(q, id, m -> {
                assertEquals(text, m.text());
                assertTrue(m.persistent());
            }, false).orElseThrow();
            assertEquals(id, received.messageId());
            assertTrue(ops.receive(q, id, m -> {
            }, false).isEmpty());
        }
    }

    @Test
    void rollbackOnlySendIsNotPublished() throws Exception {
        try (var ops = open()) {
            String q = properties.mq().testQueue();
            String id = ops.send(q, "discard", true).messageId();
            own.add(id);
            assertTrue(ops.receive(q, id, m -> {
            }, false).isEmpty());
        }
    }

    @Test
    void rejectedHandlerRollsBackAndRedelivers() throws Exception {
        try (var ops = open()) {
            String q = properties.mq().testQueue();
            String id = ops.send(q, "retry", false).messageId();
            own.add(id);
            assertThrows(IllegalArgumentException.class, () -> ops.receive(q, id, m -> {
                throw new IllegalArgumentException("reject");
            }, false));
            var m = ops.receive(q, id, x -> {
            }, false).orElseThrow();
            assertTrue(m.redelivered());
            assertTrue(m.deliveryCount() >= 2);
        }
    }

    @Test
    void separateMessageTransactionsKeepEarlierCommitOnLaterHandlerFailure() throws Exception {
        try (var ops = open()) {
            String q = properties.mq().testQueue();
            String first = ops.send(q, "first", false).messageId(), second = ops.send(q, "second",
                    false).messageId();
            own.add(first);
            own.add(second);
            ops.receive(q, first, m -> {
            }, false);
            assertThrows(IllegalArgumentException.class, () -> ops.receive(q, second, m -> {
                throw new IllegalArgumentException();
            }, false));
            assertTrue(ops.receive(q, first, m -> {
            }, false).isEmpty());
            assertTrue(ops.receive(q, second, m -> {
            }, false).isPresent());
        }
    }
}
