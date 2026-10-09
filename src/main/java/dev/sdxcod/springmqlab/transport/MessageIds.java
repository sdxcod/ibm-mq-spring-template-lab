package dev.sdxcod.springmqlab.transport;

import java.util.Locale;

public final class MessageIds {
    private MessageIds() {
    }

    public static String selector(String id) {
        if (id == null || id.isBlank())
            return null;

        String hex = id.startsWith("ID:") ? id.substring(3) : id;

        if (!hex.matches("[0-9a-fA-F]{48}"))
            throw new IllegalArgumentException("Message ID must be ID: plus 48 hex characters");

        return "JMSMessageID = 'ID:" + hex.toUpperCase(Locale.ROOT) + "'";
    }
}
