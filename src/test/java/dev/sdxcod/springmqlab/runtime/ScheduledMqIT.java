package dev.sdxcod.springmqlab.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.sdxcod.springmqlab.config.LabProperties;
import dev.sdxcod.springmqlab.config.QueueSettingsClient;
import dev.sdxcod.springmqlab.config.SettingsRegistry;
import dev.sdxcod.springmqlab.storage.FileOutbox;
import dev.sdxcod.springmqlab.storage.InwardJournal;
import dev.sdxcod.springmqlab.transport.IbmMqOperationsFactory;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Live MQ plus real HTTP settings; deterministic cycles isolate transaction/count assertions.
 */
class ScheduledMqIT {
    @TempDir
    Path data;

    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }

    @Test
    void remoteRevisionChangesBatchCapsAndResumesAfterConnectionFailure() throws Exception {
        String in = "DEV.LAB.IT.IN", out = "DEV.LAB.IT.OUT";
        int port = Integer.parseInt(env("LAB_MQ_PORT", "1414"));
        var body = new AtomicReference<>(new JSONObject(Files.readString(Path.of("mock/queue-settings.json")))
                .put("host", env("LAB_MQ_HOST", "localhost"))
                .put("port", port)
                .put("queue-manager", env("LAB_MQ_QMGR", "QM1"))
                .put("channel", env("LAB_MQ_CHANNEL", "DEV.APP.SVRCONN"))
                .put("inward-queue", in).put("outward-queue", out)
                .put("receive-timeout-ms", 0)
                .put("failure-retry-ms", 100).put("inward-batch-count", 2)
                .put("outward-batch-count", 3).toString());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/queue-settings", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        });
        server.start();
        var p = new LabProperties("once", data,
                Path.of(env("LAB_MQ_PASSWORD_FILE", ".secrets/mqAppPassword")),
                env("LAB_MQ_PASSWORD", ""),
                env("LAB_MQ_USER", "app"),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/queue-settings"),
                100,
                100,
                15000,
                2000,
                100,
                "FAIL:",
                new LabProperties.LocalMq(
                        env("LAB_MQ_HOST", "localhost"),
                        port,
                        env("LAB_MQ_QMGR", "QM1"),
                        env("LAB_MQ_CHANNEL", "DEV.APP.SVRCONN"),
                        in,
                        out,
                        "DEV.LAB.TEST"));
        var factory = new IbmMqOperationsFactory(p);
        var outbox = new FileOutbox(data);
        var journal = new InwardJournal(data, "FAIL:");
        var ids = new ArrayList<String>();
        try (var runtime = new AgentRuntime(p, new SettingsRegistry(15000,
                () -> System.nanoTime() / 1_000_000),
                new QueueSettingsClient(p.settingsUrl(),
                        java.time.Duration.ofMillis(p.httpTimeoutMs())), factory, outbox, journal)) {

            // Dedicated reserved queues must be empty: no shared queues are purged or consumed.
            try (var ops = factory.open(p.localSettings())) {
                assertEquals(0, ops.browse(in, null, 1),
                        "Reserved IT inward queue must be empty");
                assertEquals(0, ops.browse(out, null, 1),
                        "Reserved IT outward queue must be empty");
                for (int i = 0; i < 5; i++)
                    ids.add(ops.send(in, "IT " + UUID.randomUUID(), false).messageId());
            }

            for (int i = 0; i < 7; i++)
                outbox.enqueue("scheduled IT " + i);

            body.set(new JSONObject(body.get()).put("port", 1).toString());
            runtime.pollSettings();
            assertEquals(100, runtime.runInward());
            assertEquals(100, runtime.runOutward());
            assertEquals(7, outbox.counts().get("ready"));
            assertEquals(0, outbox.counts().get("uncertain"));
            body.set(new JSONObject(body.get()).put("revision", 2).put("port", port).toString());
            runtime.pollSettings();
            runtime.runInward();
            runtime.runOutward();
            assertEquals(2, journalCount());
            assertEquals(3, outbox.counts().get("sent"));
            assertEquals(4, outbox.counts().get("ready"));
            body.set(new JSONObject(body.get()).put("revision", 3).put("enabled", false).toString());
            runtime.pollSettings();
            runtime.runInward();
            runtime.runOutward();
            assertEquals(2, journalCount());
            assertEquals(3, outbox.counts().get("sent"));
            body.set(new JSONObject(body.get())
                    .put("revision", 4)
                    .put("enabled", true)
                    .put("inward-batch-count", 3)
                    .put("outward-batch-count", 2).toString());
            runtime.pollSettings();
            runtime.runInward();
            runtime.runOutward();
            assertEquals(5, journalCount());
            assertEquals(5, outbox.counts().get("sent"));
            assertEquals(2, outbox.counts().get("ready"));
        } finally {
            server.stop(0);
            try (var ops = factory.open(p.localSettings())) {
                for (String id : ids)
                    ops.receive(in, id, m -> {
                    }, false);
                try (var files = Files.list(data.resolve("outward/sent"))) {
                    for (Path file : files.toList())
                        ops.receive(out, new JSONObject(Files.readString(file))
                                .getString("mq-message-id"), m -> {
                        }, false);
                }
            }
        }
    }

    private long journalCount() throws Exception {
        try (var files = Files.list(data.resolve("inward"))) {
            return files.filter(p -> p.toString().endsWith(".json")).count();
        }
    }
}
