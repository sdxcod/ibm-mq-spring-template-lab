package dev.sdxcod.springmqlab.transport;

import dev.sdxcod.springmqlab.config.QueueSettings;

@FunctionalInterface
public interface MqOperationsFactory {
    MqOperations open(QueueSettings settings) throws Exception;
}
