package dev.sdxcod.springmqlab.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Prevent two scheduler processes from consuming the same local spool.
 */
public final class AgentLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private AgentLease(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static AgentLease acquire(Path data) throws IOException {
        Files.createDirectories(data);
        var channel = FileChannel.open(data.resolve(".agent.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null)
                throw new IOException("Another agent owns this data directory");

            return new AgentLease(channel, lock);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
