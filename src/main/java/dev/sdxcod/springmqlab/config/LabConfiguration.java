package dev.sdxcod.springmqlab.config;

import dev.sdxcod.springmqlab.runtime.AgentRuntime;
import dev.sdxcod.springmqlab.storage.FileOutbox;
import dev.sdxcod.springmqlab.storage.InwardJournal;
import dev.sdxcod.springmqlab.transport.IbmMqOperationsFactory;
import dev.sdxcod.springmqlab.transport.MqOperationsFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;

@Configuration
public class LabConfiguration {
    @Bean
    SettingsRegistry settingsRegistry(LabProperties p) {
        return new SettingsRegistry(p.settingsMaxAgeMs(), () -> System.nanoTime() / 1000000);
    }

    @Bean
    QueueSettingsClient settingsClient(LabProperties p) {
        return new QueueSettingsClient(p.settingsUrl(), Duration.ofMillis(p.httpTimeoutMs()));
    }

    @Bean
    MqOperationsFactory operationsFactory(LabProperties p) {
        return new IbmMqOperationsFactory(p);
    }

    @Bean
    FileOutbox outbox(LabProperties p) throws IOException {
        return new FileOutbox(p.dataDirectory());
    }

    @Bean
    InwardJournal journal(LabProperties p) throws IOException {
        return new InwardJournal(p.dataDirectory(), p.rejectPrefix());
    }

    @Bean(destroyMethod = "close")
    AgentRuntime runtime(LabProperties p, SettingsRegistry r, QueueSettingsClient c, MqOperationsFactory f,
                         FileOutbox o, InwardJournal j) {
        return new AgentRuntime(p, r, c, f, o, j);
    }
}
