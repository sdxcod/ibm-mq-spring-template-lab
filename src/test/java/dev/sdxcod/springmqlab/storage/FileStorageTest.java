package dev.sdxcod.springmqlab.storage;

import dev.sdxcod.springmqlab.transport.ReceivedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageTest {
    @TempDir
    Path data;

    @Test
    void enqueueClaimCommitAndRetryPreserveWorkIdentity() throws Exception {
        var out = new FileOutbox(data);
        var work = out.enqueue("hello");
        assertEquals(work, out.claim().orElseThrow());
        out.retry(work);
        assertEquals(work, out.claim().orElseThrow());
        out.sent(work, "ID:" + "A1".repeat(24));
        assertEquals(1, out.counts().get("sent"));
        assertEquals(0, out.counts().get("ready"));
        assertTrue(Files.readString(data.resolve("outward/sent/" + work.id() + ".json")).contains("mq-message-id"));
    }

    @Test
    void openingCliStorageDoesNotStealLiveClaimsAndStartupRecoveryQuarantinesThem() throws Exception {
        var out = new FileOutbox(data);
        var work = out.enqueue("hello");
        out.claim();
        var another = new FileOutbox(data);
        assertEquals(1, another.counts().get("in-flight"));
        another.recoverInFlight();
        assertEquals(1, another.counts().get("uncertain"));
        assertTrue(another.claim().isEmpty());
        another.requeue(work.id());
        assertEquals(work, another.claim().orElseThrow());
    }

    @Test
    void unknownWorkIsNeverAutomaticallyClaimedAgain() throws Exception {
        var out = new FileOutbox(data);
        var work = out.enqueue("hello");
        out.claim();
        out.uncertain(work, "commit lost");
        assertTrue(out.claim().isEmpty());
        assertEquals(1, out.counts().get("uncertain"));
        assertThrows(IllegalArgumentException.class, () -> out.requeue("../../escape"));
    }

    @Test
    void agentLeasePreventsSecondSchedulerAndCanBeReacquired() throws Exception {
        try (var lease = AgentLease.acquire(data)) {
            assertThrows(Exception.class, () -> AgentLease.acquire(data));
        }
        try (var lease = AgentLease.acquire(data)) {
            assertNotNull(lease);
        }
    }

    @Test
    void inwardJournalHandlesRedeliveryIdempotentlyAndRejectsConflictingPayload() throws Exception {
        var journal = new InwardJournal(data, "FAIL:");
        String id = "ID:" + "AB".repeat(24);
        journal.accept(new ReceivedMessage(id, "hello", false, 1, true));
        journal.accept(new ReceivedMessage(id, "hello", true, 2, true));
        try (var paths = Files.list(data.resolve("inward"))) {
            assertEquals(1, paths.count());
        }
        assertThrows(java.io.UncheckedIOException.class,
                () -> journal.accept(new ReceivedMessage(id, "different", true, 3,
                        true)));
        assertThrows(IllegalArgumentException.class,
                () -> journal.accept(new ReceivedMessage(id, "FAIL: test", false, 1,
                        true)));
    }
}
