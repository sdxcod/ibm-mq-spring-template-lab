package dev.sdxcod.springmqlab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

@ConfigurationProperties("lab")
public record LabProperties(String command,
                            Path dataDirectory,
                            Path passwordFile,
                            String password,
                            String user,
                            URI settingsUrl,
                            long settingsPollMs,
                            long settingsFailureMs,
                            long settingsMaxAgeMs,
                            long httpTimeoutMs,
                            long idleDelayMs,
                            String rejectPrefix,
                            LocalMq mq) {

    public record LocalMq(String host,
                          int port,
                          String queueManager,
                          String channel,
                          String inQueue,
                          String outQueue,
                          String testQueue) {
    }

    public LabProperties {
        if (dataDirectory == null || user == null || user.isBlank() || settingsUrl == null
                || !java.util.Set.of("http", "https").contains(settingsUrl.getScheme()))
            throw new IllegalArgumentException("Invalid lab configuration");

        if (settingsPollMs < 10 || settingsFailureMs < 10 || settingsMaxAgeMs < settingsPollMs
                || httpTimeoutMs < 10 || idleDelayMs < 10)
            throw new IllegalArgumentException("Invalid scheduler/lease timing");
    }

    public String resolvePassword() throws IOException {

        if (password != null && !password.isEmpty())
            return password;

        if (passwordFile == null || !Files.isRegularFile(passwordFile))
            throw new IOException("MQ password file missing; run scripts/init-lab.sh");

        String result = Files.readString(passwordFile);

        if (result.isEmpty())
            throw new IOException("Empty MQ password file");
        return result;
    }

    public QueueSettings localSettings() {
        return new QueueSettings(1,
                true,
                mq.host(),
                mq.port(),
                mq.queueManager(),
                mq.channel(),
                mq.inQueue(),
                mq.outQueue(),
                5,
                5,
                2000,
                3000,
                3000,
                1000);
    }

    public String queue(String alias) {
        return switch (alias) {
            case "in" -> mq.inQueue();
            case "out" -> mq.outQueue();
            case "test" -> mq.testQueue();
            default -> throw new IllegalArgumentException("Queue must be in/out/test");
        };
    }

    @Override
    public String toString() {
        return "LabProperties[dataDirectory=" + dataDirectory + ", user=" + user + ", password=<redacted>]";
    }
}
