package dev.sdxcod.springmqlab.cli;

import dev.sdxcod.springmqlab.config.LabProperties;
import dev.sdxcod.springmqlab.config.QueueSettingsClient;
import dev.sdxcod.springmqlab.runtime.AgentRuntime;
import dev.sdxcod.springmqlab.storage.AgentLease;
import dev.sdxcod.springmqlab.storage.FileOutbox;
import dev.sdxcod.springmqlab.transport.MqOperationsFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

@Component
public final class LabRunner implements ApplicationRunner {
    private final LabProperties p;
    private final Environment env;
    private final MqOperationsFactory factory;
    private final AgentRuntime runtime;
    private final FileOutbox outbox;
    private final QueueSettingsClient client;

    public LabRunner(LabProperties p, Environment env, MqOperationsFactory factory, AgentRuntime runtime,
                     FileOutbox outbox, QueueSettingsClient client) {
        this.p = p;
        this.env = env;
        this.factory = factory;
        this.runtime = runtime;
        this.outbox = outbox;
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String command = p.command();
        String queue = p.queue(env.getProperty("lab.queue", "in"));
        String outcome = env.getProperty("lab.outcome", "commit").toLowerCase(java.util.Locale.ROOT);

        if (!java.util.Set.of("commit", "rollback", "backout").contains(outcome))
            throw new IllegalArgumentException("Invalid outcome");

        boolean rollback = !outcome.equals("commit");

        switch (command) {
            case "help" -> help();
            case "agent" -> {
                try (var ignored = AgentLease.acquire(p.dataDirectory())) {
                    outbox.recoverInFlight();
                    runtime.start();
                    System.out.println("Agent running; Ctrl+C stops new work and closes scopes.");
                    new CountDownLatch(1).await();
                }
            }
            case "settings" -> System.out.println(client.fetch());
            case "once" -> {
                try (var ignored = AgentLease.acquire(p.dataDirectory())) {
                    outbox.recoverInFlight();
                    runtime.pollSettings();
                    runtime.runInward();
                    runtime.runOutward();
                }
            }
            case "enqueue" -> {
                int n = env.getProperty("lab.count", Integer.class, 1);
                if (n < 1 || n > 10000) throw new IllegalArgumentException("Count must be 1..10000");
                for (int i = 0; i < n; i++)
                    System.out.println(outbox.enqueue(env.getProperty("lab.message",
                            "Scheduled message") + (n > 1 ? " #" + i : "")));
            }
            case "status" -> System.out.println(outbox.counts());
            case "requeue" -> {
                outbox.requeue(env.getRequiredProperty("lab.work-id"));
                System.out.println("Explicitly requeued; verify MQ outcome before replay");
            }
            case "shell" -> shell();
            case "demo" -> demo();
            case "send", "receive", "browse", "depth" -> {
                try (var ops = factory.open(p.localSettings())) {
                    switch (command) {
                        case "send" -> System.out.println(ops.send(queue, env.getProperty("lab.message",
                                "Hello Spring JMS"), rollback));
                        case "receive" -> System.out.println(ops.receive(queue, env.getProperty("lab.message-id"),
                                m -> System.out.println("Inside transaction: " + m), rollback));
                        default -> System.out.println("Browse observation=" +
                                ops.browse(queue, env.getProperty("lab.message-id"),
                                        env.getProperty("lab.browse-limit",
                                                Integer.class, 100)));
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private void shell() throws Exception {

        System.out.println("Each put/get uses its OWN Spring transaction. No pending work between commands.");
        System.out.println("put in TEXT | put-rollback out TEXT | get in [ID] | get-rollback in [ID] | browse in | settings | status | quit");

        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        try (var ops = factory.open(p.localSettings())) {
            while (true) {
                System.out.print("spring-mq> ");
                System.out.flush();

                String line = input.readLine();

                if (line == null)
                    return;

                String[] parts = line.trim().split("\\s+", 3);

                if (parts[0].isBlank())
                    continue;
                try {
                    switch (parts[0]) {
                        case "quit", "exit" -> {
                            return;
                        }
                        case "put", "put-rollback" -> System.out.println(ops.send(p.queue(
                                        arg(parts, 1)),
                                arg(parts, 2),
                                parts[0].equals("put-rollback")));
                        case "get", "get-rollback" ->
                                System.out.println(ops.receive(p.queue(arg(parts, 1)), parts.length > 2 ?
                                        parts[2] : null, System.out::println, parts[0].equals("get-rollback")));
                        case "browse", "depth" -> System.out.println("Browse observation=" +
                                ops.browse(p.queue(arg(parts, 1)), null, 100));
                        case "settings" -> System.out.println(client.fetch());
                        case "status" -> System.out.println(outbox.counts());
                        default ->
                                System.out.println("Unknown command; transactions complete automatically per put/get");
                    }
                } catch (Exception e) {
                    System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
    }

    private static String arg(String[] p, int i) {
        if (p.length <= i) throw new IllegalArgumentException("Missing argument");
        return p[i];
    }

    private void demo() throws Exception {
        String q = p.mq().testQueue(), id = null;
        String text = "Spring demo " + UUID.randomUUID() + " | سلام";

        try (var ops = factory.open(p.localSettings())) {
            var canceled = ops.send(q, "discard " + text, true);

            if (ops.receive(q, canceled.messageId(), m -> {
            }, false).isPresent())
                throw new IllegalStateException("Rolled-back send visible");

            System.out.println("PASS: Spring rolls back a send when rollback-only is set");

            id = ops.send(q, text, false).messageId();

            try {
                ops.receive(q, id, m -> {
                    throw new IllegalArgumentException("handler rejected");
                }, false);

                throw new IllegalStateException("Handler failure swallowed");
            } catch (IllegalArgumentException expected) {
                if (!expected.getMessage().equals("handler rejected"))
                    throw expected;
            }

            var again = ops.receive(q, id, m -> {
                if (!m.text().equals(text))
                    throw new IllegalStateException("Text changed");
            }, false).orElseThrow();

            if (!again.redelivered() || again.deliveryCount() < 2)
                throw new IllegalStateException("Expected redelivery");

            System.out.println("PASS: handler failure causes Spring rollback and redelivery");

            if (ops.receive(q, id, m -> {
            }, false).isPresent())
                throw new IllegalStateException("Committed receive not removed");

            System.out.println("PASS: successful receive commits and removes message");
        } finally {
            if (id != null) try (var ops = factory.open(p.localSettings())) {
                ops.receive(q, id, m -> {
                }, false);
            }
        }
    }

    private void help() {
        System.out.println("IBM MQ Spring JmsTemplate Lab: agent|once|settings|send|receive|browse|demo|shell|enqueue|status|requeue");
        System.out.println("Options: --lab.queue=in|out|test --lab.message=TEXT --lab.outcome=commit|rollback --lab.count=10 --lab.message-id=ID:48_HEX");
        System.out.println("MQ transactions are managed by Spring; no JMS listener is installed.");
    }
}
