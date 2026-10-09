package dev.sdxcod.springmqlab.config;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class SettingsRegistry {
    public record Snapshot(QueueSettings settings, long generation, long receivedAtMs) {
    }

    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final LongSupplier clock;
    private final long maxAgeMs;

    public SettingsRegistry(long maxAgeMs, LongSupplier clock) {
        this.maxAgeMs = maxAgeMs;
        this.clock = clock;
    }

    public synchronized boolean accept(QueueSettings settings) {
        Snapshot old = current.get();

        if (old != null && settings.revision() < old.settings().revision())
            throw new IllegalArgumentException("Settings revision went backwards");

        if (old != null && settings.revision() == old.settings().revision() && !settings.equals(old.settings()))
            throw new IllegalArgumentException("Changed settings require a newer revision");

        boolean changed = old == null || !old.settings().equals(settings);
        long generation = old == null ? 1 : old.generation() + (changed ? 1 : 0);
        current.set(new Snapshot(settings, generation, clock.getAsLong()));

        return changed;
    }

    public Snapshot forWork() {
        Snapshot s = current.get();

        return s != null && s.settings().enabled() && clock.getAsLong() - s.receivedAtMs() <= maxAgeMs ? s : null;
    }

    public boolean permits(Snapshot snapshot) {
        Snapshot now = forWork();

        return now != null && now.generation() == snapshot.generation();
    }

    public Snapshot current() {
        return current.get();
    }
}
