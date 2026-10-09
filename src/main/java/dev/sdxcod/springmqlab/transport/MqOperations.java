package dev.sdxcod.springmqlab.transport;

import java.util.Optional;
import java.util.function.Consumer;

public interface MqOperations extends AutoCloseable {

    SendResult send(String queue, String text, boolean rollback);

    Optional<ReceivedMessage> receive(String queue, String id, Consumer<ReceivedMessage> handler, boolean rollback);

    int browse(String queue, String id, int limit);

    @Override
    void close();
}
