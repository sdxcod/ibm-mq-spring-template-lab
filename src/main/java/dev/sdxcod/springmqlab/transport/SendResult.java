package dev.sdxcod.springmqlab.transport;

public record SendResult(String messageId, boolean committed) {
}
