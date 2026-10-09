package dev.sdxcod.springmqlab.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import org.json.JSONObject;

/**
 * Local educational spool: one agent process only, no distributed claim or database transaction.
 */
public final class FileOutbox {
    public record Work(String id, String text) {
        public String envelope() {
            return new JSONObject().put("work-id", id).put("payload", text).toString();
        }
    }

    private final Path root;

    public FileOutbox(Path data) throws IOException {
        root = data.resolve("outward");

        for (String d : List.of("ready", "in-flight", "sent", "uncertain"))
            Files.createDirectories(root.resolve(d));

    }

    /**
     * Call only while owning the agent process lease. CLI enqueue/status never recover active claims.
     */
    public synchronized void recoverInFlight() throws IOException {
        for (Path path : list("in-flight"))
            move(path, root.resolve("uncertain").resolve(path.getFileName()));
    }

    public synchronized Work enqueue(String text) throws IOException {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Empty work payload");

        Work work = new Work(UUID.randomUUID().toString(), text);


        if (work.envelope().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576)
            throw new IllegalArgumentException("Envelope exceeds 1 MiB");

        write(root.resolve("ready").resolve(work.id() + ".json"),
                new JSONObject().put("id", work.id()).put("text", text)
                        .put("created-at", java.time.Instant.now().toString()));
        return work;
    }

    public synchronized Optional<Work> claim() throws IOException {
        var paths = list("ready");

        if (paths.isEmpty())
            return Optional.empty();

        Path path = paths.getFirst();
        var o = new JSONObject(Files.readString(path));
        Work work = new Work(o.getString("id"), o.getString("text"));

        validateId(work.id());
        move(path, file("in-flight", work));
        return Optional.of(work);
    }

    public synchronized void retry(Work work) throws IOException {
        move(file("in-flight", work), file("ready", work));
    }

    public synchronized void sent(Work work, String mqId) throws IOException {
        Path source = file("in-flight", work);
        var o = new JSONObject(Files.readString(source));
        o.put("mq-message-id", mqId).put("mq-commit-confirmed", true);
        write(source, o);
        move(source, file("sent", work));
    }

    public synchronized void uncertain(Work work, String reason) throws IOException {
        Path source = file("in-flight", work);
        if (!Files.exists(source)) return;
        var o = new JSONObject(Files.readString(source));
        o.put("review-reason", reason);
        write(source, o);
        move(source, file("uncertain", work));
    }

    public synchronized void requeue(String id) throws IOException {
        validateId(id);
        move(root.resolve("uncertain").resolve(id + ".json"), root.resolve("ready")
                .resolve(id + ".json"));
    }

    public synchronized Map<String, Integer> counts() throws IOException {
        var result = new LinkedHashMap<String, Integer>();
        for (String d : List.of("ready", "in-flight", "sent", "uncertain")) result.put(d, list(d).size());
        return result;
    }

    private Path file(String folder, Work work) {
        validateId(work.id());
        return root.resolve(folder).resolve(work.id() + ".json");
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}") || !UUID.fromString(id).toString().equalsIgnoreCase(id))
            throw new IllegalArgumentException("Invalid work ID");
    }

    private List<Path> list(String folder) throws IOException {
        try (var paths = Files.list(root.resolve(folder))) {
            return paths.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static void move(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void write(Path path, JSONObject json) throws IOException {
        Path tmp = Files.createTempFile(path.getParent(), "work-", ".tmp");
        try {
            Files.writeString(tmp, json.toString(2));
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
