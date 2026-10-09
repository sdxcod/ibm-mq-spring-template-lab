package dev.sdxcod.springmqlab.config;

import org.json.JSONObject;

public record QueueSettings(long revision,
                            boolean enabled,
                            String host,
                            int port,
                            String queueManager,
                            String channel,
                            String inwardQueue,
                            String outwardQueue,
                            int inwardBatchCount,
                            int outwardBatchCount,
                            long inwardIntervalMs,
                            long outwardIntervalMs,
                            long failureRetryMs,
                            long receiveTimeoutMs) {

    public QueueSettings {
        if (revision < 1 || host == null || host.isBlank() || host.length() > 255 || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid revision/host/port");

        for (String name : new String[]{queueManager, channel, inwardQueue, outwardQueue})
            if (name == null || !name.matches("[A-Za-z0-9._/%]{1,48}"))
                throw new IllegalArgumentException("Invalid MQ object name");

        if (inwardBatchCount < 1 || inwardBatchCount > 1000 || outwardBatchCount < 1 || outwardBatchCount > 1000)
            throw new IllegalArgumentException("Batch counts must be 1..1000");

        if (inwardIntervalMs < 10 || outwardIntervalMs < 10 || failureRetryMs < 10 || inwardIntervalMs > 3600000
                || outwardIntervalMs > 3600000 || failureRetryMs > 3600000 || receiveTimeoutMs < 0 || receiveTimeoutMs > 5000)
            throw new IllegalArgumentException("Invalid interval or receive timeout");
    }

    public static QueueSettings parse(String json) {

        var o = new JSONObject(json);
        // Exact integer conversion rejects fractional or out-of-range JSON values.
        return new QueueSettings(
                number(o, "revision"),
                bool(o, "enabled"),
                o.getString("host"),
                Math.toIntExact(number(o, "port")),
                o.getString("queue-manager"),
                o.getString("channel"),
                o.getString("inward-queue"),
                o.getString("outward-queue"),
                Math.toIntExact(number(o, "inward-batch-count")),
                Math.toIntExact(number(o, "outward-batch-count")),
                number(o, "inward-interval-ms"),
                number(o, "outward-interval-ms"),
                number(o, "failure-retry-ms"),
                number(o, "receive-timeout-ms"));
    }

    private static boolean bool(JSONObject o, String key) {
        if (!(o.get(key) instanceof Boolean value))
            throw new IllegalArgumentException(key + " must be a boolean");
        return value;
    }

    private static long number(JSONObject o, String key) {
        Object value = o.get(key);

        if (!(value instanceof Number n))
            throw new IllegalArgumentException(key + " must be a number");

        return new java.math.BigDecimal(n.toString()).longValueExact();
    }
}
