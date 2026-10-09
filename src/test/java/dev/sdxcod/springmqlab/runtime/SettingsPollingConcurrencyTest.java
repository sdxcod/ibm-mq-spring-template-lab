package dev.sdxcod.springmqlab.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.sdxcod.springmqlab.config.LabProperties;
import dev.sdxcod.springmqlab.config.QueueSettingsClient;
import dev.sdxcod.springmqlab.config.SettingsRegistry;
import dev.sdxcod.springmqlab.storage.FileOutbox;
import dev.sdxcod.springmqlab.transport.MqOperations;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettingsPollingConcurrencyTest {
    @TempDir
    Path data;

    @Test
    void settingsContinueUpdatingWhileMqWorkerIsBlocked() throws Exception {

        var json = new AtomicReference<>(Files.readString(Path.of("mock/queue-settings.json")));
        var server = server(json);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var p = properties(server);
        var registry = new SettingsRegistry(1000, () -> System.nanoTime() / 1_000_000);
        var ops = mock(MqOperations.class);

        doAnswer(a -> {
            closed.countDown();
            return null;
        }).when(ops).close();
        try (var runtime = new AgentRuntime(p, registry, new QueueSettingsClient(p.settingsUrl(),
                Duration.ofMillis(200)), s -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS))
                throw new IllegalStateException("Test MQ gate timed out");
            return ops;
        }, new FileOutbox(data), m -> {
        })) {
            runtime.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            json.set(new JSONObject(json.get()).put("revision", 2).put("enabled", false).put("port", 1415).toString());
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> registry.current() != null && registry.current().settings().revision() == 2);
            assertFalse(registry.current().settings().enabled());
            assertEquals(1, release.getCount());
            release.countDown();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            verify(ops, never()).receive(anyString(), any(), any(), anyBoolean());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void pollingForOnceDoesNotStartBackgroundMqCycles() throws Exception {
        var json = new AtomicReference<>(Files.readString(Path.of("mock/queue-settings.json")));
        var server = server(json);
        var p = properties(server);
        var opens = new AtomicInteger();

        try (var runtime = new AgentRuntime(p, new SettingsRegistry(1000,
                () -> System.nanoTime() / 1_000_000), new QueueSettingsClient(p.settingsUrl(),
                Duration.ofMillis(200)),
                s -> {
                    opens.incrementAndGet();
                    return mock(MqOperations.class);
                }, new FileOutbox(data), m -> {
        })) {
            runtime.pollSettings();
            assertEquals(0, opens.get());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(AtomicReference<String> json) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/settings", e -> {
            byte[] bytes = json.get().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length);
            try (var out = e.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private LabProperties properties(HttpServer server) {
        return new LabProperties(
                "agent", data,
                null,
                "test-password",
                "app",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/settings"),
                25,
                25,
                1000,
                200,
                20,
                "FAIL:",
                new LabProperties.LocalMq(
                        "localhost",
                        1414,
                        "QM1",
                        "DEV.APP.SVRCONN",
                        "DEV.LAB.IN",
                        "DEV.LAB.OUT",
                        "DEV.LAB.TEST"));
    }
}
