package dev.sdxcod.springmqlab.transport;

/**
 * RETRYABLE means send never began or Spring confirmed rollback. UNKNOWN requires reconciliation.
 */
public final class SendFailure extends RuntimeException {
    public enum Kind {RETRYABLE, UNKNOWN}

    private final Kind kind;

    public SendFailure(Kind kind, Throwable cause) {
        super("MQ send failed; outcome=" + kind, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
