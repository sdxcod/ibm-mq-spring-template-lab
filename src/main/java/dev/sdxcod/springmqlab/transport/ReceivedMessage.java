package dev.sdxcod.springmqlab.transport;

public record ReceivedMessage(String messageId,
                              String text,
                              boolean redelivered,
                              int deliveryCount,
                              boolean persistent) {
}
