package dev.sdxcod.springmqlab.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueueSettingsClientTest {
    @Test
    void realHttpRoundtripParsesSettingsAndRejects503AndMalformedPayload() throws Exception {

        var body = new AtomicReference<>(Files.readString(Path.of("mock/queue-settings.json")));
        var code = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/queue-settings", e -> {
            byte[] data = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(code.get(), data.length);
            e.getResponseBody().write(data);
            e.close();
        });

        server.start();

        try {
            var c = new QueueSettingsClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/queue-settings"),
                    Duration.ofSeconds(2));
            assertEquals(5, c.fetch().inwardBatchCount());
            code.set(503);
            assertThrows(IOException.class, c::fetch);
            code.set(200);
            body.set("invalid-json");
            assertThrows(RuntimeException.class, c::fetch);
        } finally {
            server.stop(0);
        }
    }
}
