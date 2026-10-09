package dev.sdxcod.springmqlab.storage;

import dev.sdxcod.springmqlab.transport.ReceivedMessage;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.function.Consumer;

public final class InwardJournal implements Consumer<ReceivedMessage> {
    private final Path directory;
    private final String rejectPrefix;

    public InwardJournal(Path data, String rejectPrefix) throws IOException {
        directory = data.resolve("inward");
        Files.createDirectories(directory);
        this.rejectPrefix = rejectPrefix;
    }

    @Override
    public void accept(ReceivedMessage message) {
        if (rejectPrefix != null && !rejectPrefix.isEmpty() && message.text().startsWith(rejectPrefix))
            throw new IllegalArgumentException("Simulated handler rejection");

        String id = message.messageId();
        MessageIdGuard.check(id);
        Path file = directory.resolve(id.substring(3).toUpperCase(java.util.Locale.ROOT) + ".json");

        try {
            if (Files.exists(file)) {
                // Idempotent local handler across MQ redelivery.
                if (!new JSONObject(Files.readString(file)).getString("text").equals(message.text()))
                    throw new IOException("Existing ID has different payload");
                return;
            }

            Path tmp = Files.createTempFile(directory, "message-", ".tmp");

            try {
                Files.writeString(tmp, new JSONObject().put("message-id", id).put("text", message.text())
                        .put("delivery-count", message.deliveryCount()).put("recorded-at", Instant.now().toString())
                        .toString(2));
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class MessageIdGuard {
        static void check(String id) {
            if (id == null || !id.matches("ID:[0-9a-fA-F]{48}"))
                throw new IllegalArgumentException("Invalid IBM message ID");
        }
    }
}
