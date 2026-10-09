package dev.sdxcod.springmqlab.runtime;

import dev.sdxcod.springmqlab.config.LabProperties;
import dev.sdxcod.springmqlab.config.QueueSettings;
import dev.sdxcod.springmqlab.config.SettingsRegistry;
import dev.sdxcod.springmqlab.storage.FileOutbox;
import dev.sdxcod.springmqlab.transport.MqOperations;
import dev.sdxcod.springmqlab.transport.ReceivedMessage;
import dev.sdxcod.springmqlab.transport.SendFailure;
import dev.sdxcod.springmqlab.transport.SendResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AgentRuntimeTest {
    @TempDir
    Path data;

    private LabProperties properties() {
        return new LabProperties("help", data, null, "test-password", "app",
                URI.create("http://localhost:8088/queue-settings"), 100, 100,
                1000, 100, 50, "FAIL:",
                new LabProperties.LocalMq("localhost", 1414, "QM1", "DEV.APP.SVRCONN",
                        "DEV.LAB.IN", "DEV.LAB.OUT", "DEV.LAB.TEST"));
    }

    private QueueSettings settings(long revision, boolean enabled, int in, int out) {
        return new QueueSettings(revision, enabled, "localhost", 1414, "QM1",
                "DEV.APP.SVRCONN", "DEV.LAB.IN", "DEV.LAB.OUT", in, out,
                200, 300, 500, 0);
    }

    @Test
    void inwardCountIsMaximumPerConnectionAndNoOverlapIsNeeded() throws Exception {
        var registry = new SettingsRegistry(1000, () -> 0);
        registry.accept(settings(1, true, 2, 3));
        var ops = mock(MqOperations.class);
        when(ops.receive(anyString(), isNull(), any(), eq(false))).thenReturn(
                Optional.of(new ReceivedMessage("id", "x", false, 1,
                        true)));
        var opens = new AtomicInteger();
        try (var runtime = new AgentRuntime(properties(), registry, null, s -> {
            opens.incrementAndGet();
            return ops;
        }, new FileOutbox(data), m -> {
        })) {
            assertEquals(200, runtime.runInward());
            verify(ops, times(2)).receive(anyString(), isNull(), any(), eq(false));
            verify(ops).close();
            assertEquals(1, opens.get());
        }
    }

    @Test
    void emptyReceiveStopsCycleEarly() throws Exception {
        var registry = new SettingsRegistry(1000, () -> 0);
        registry.accept(settings(1, true, 8, 3));
        var ops = mock(MqOperations.class);
        when(ops.receive(anyString(), isNull(), any(), eq(false))).thenReturn(Optional.empty());
        try (var runtime = new AgentRuntime(properties(), registry, null, s -> ops,
                new FileOutbox(data), m -> {
        })) {
            runtime.runInward();
            verify(ops, times(1)).receive(anyString(), isNull(), any(), eq(false));
        }
    }

    @Test
    void disableDuringCyclePreventsNextMessageWithoutInterruptingCurrentOne() throws Exception {
        var registry = new SettingsRegistry(1000, () -> 0);
        registry.accept(settings(1, true, 8, 3));
        var ops = mock(MqOperations.class);
        when(ops.receive(anyString(), isNull(), any(), eq(false))).thenAnswer(a -> {
            registry.accept(settings(2, false, 8, 3));
            return Optional.of(new ReceivedMessage("id", "x", false, 1,
                    true));
        });
        try (var runtime = new AgentRuntime(properties(), registry, null, s -> ops,
                new FileOutbox(data), m -> {
        })) {
            runtime.runInward();
            verify(ops, times(1)).receive(anyString(), isNull(), any(), eq(false));
            assertEquals(50, runtime.runInward());
        }
    }

    @Test
    void outwardCountLeavesExcessReadyAndCommitsOnlyBoundedWork() throws Exception {
        var registry = new SettingsRegistry(1000, () -> 0);
        registry.accept(settings(1, true, 2, 2));
        var out = new FileOutbox(data);
        for (int i = 0; i < 5; i++) out.enqueue("message" + i);
        var ops = mock(MqOperations.class);
        when(ops.send(anyString(), anyString(), eq(false))).thenReturn(new SendResult("ID",
                true));
        try (var runtime = new AgentRuntime(properties(), registry, null, s -> ops, out,
                m -> {})) {
            assertEquals(300, runtime.runOutward());
            assertEquals(2, out.counts().get("sent"));
            assertEquals(3, out.counts().get("ready"));
            verify(ops, times(2)).send(anyString(), anyString(), eq(false));
        }
    }

    @Test
    void knownRollbackReturnsWorkToReadyButUnknownCommitQuarantinesIt() throws Exception {
        var registry = new SettingsRegistry(1000, () -> 0);
        registry.accept(settings(1, true, 2, 2));
        var out = new FileOutbox(data);
        out.enqueue("one");
        var ops = mock(MqOperations.class);
        when(ops.send(anyString(), anyString(), eq(false))).thenThrow(new SendFailure(SendFailure.Kind.RETRYABLE,
                new RuntimeException()));
        try (var runtime = new AgentRuntime(properties(), registry, null, s -> ops, out,
                m -> {})) {
            assertEquals(500, runtime.runOutward());
            assertEquals(1, out.counts().get("ready"));
            doThrow(new SendFailure(SendFailure.Kind.UNKNOWN, new RuntimeException())).when(ops).send(anyString(),
                    anyString(), eq(false));
            assertEquals(500, runtime.runOutward());
            assertEquals(1, out.counts().get("uncertain"));
            assertEquals(0, out.counts().get("ready"));
            clearInvocations(ops);
            runtime.runOutward();
            verify(ops, never()).send(anyString(), anyString(), anyBoolean());
        }
    }

    @Test
    void connectionFailureUsesFailureDelayAndRecoversOnLaterCycle() throws Exception {
        var registry = new SettingsRegistry(1000, () -> 0);
        registry.accept(settings(1, true, 2, 2));
        var ops = mock(MqOperations.class);
        when(ops.receive(anyString(), isNull(), any(), eq(false))).thenReturn(Optional.empty());
        var calls = new AtomicInteger();
        try (var runtime = new AgentRuntime(properties(), registry, null, s -> {
            if (calls.getAndIncrement() == 0) throw new java.io.IOException("offline");
            return ops;
        }, new FileOutbox(data), m -> {
        })) {
            assertEquals(500, runtime.runInward());
            assertEquals(200, runtime.runInward());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void disabledOrExpiredSettingsDoNotOpenConnection() throws Exception {
        var clock = new AtomicLong();
        var registry = new SettingsRegistry(1000, clock::get);
        var opens = new AtomicInteger();
        try (var runtime = new AgentRuntime(properties(), registry, null, s -> {
            opens.incrementAndGet();
            return mock(MqOperations.class);
        }, new FileOutbox(data), m -> {
        })) {
            assertEquals(50, runtime.runInward());
            registry.accept(settings(1, false, 2, 2));
            assertEquals(50, runtime.runOutward());
            registry.accept(settings(2, true, 2, 2));
            clock.set(1001);
            runtime.runInward();
            assertEquals(0, opens.get());
        }
    }
}
