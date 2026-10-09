package dev.sdxcod.springmqlab.config;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SettingsRegistryTest {

    private String fixture() throws Exception {
        return Files.readString(java.nio.file.Path.of("mock/queue-settings.json"));
    }

    @Test
    void refreshDoesNotInvalidateActiveGenerationButChangedRevisionDoes() throws Exception {
        var clock = new AtomicLong(0);
        var r = new SettingsRegistry(100, clock::get);
        var s = QueueSettings.parse(fixture());
        assertTrue(r.accept(s));
        var active = r.forWork();
        clock.set(50);
        assertFalse(r.accept(s));
        assertTrue(r.permits(active));
        var changed = QueueSettings.parse(new JSONObject(fixture())
                .put("revision", 2)
                .put("outward-batch-count", 9).toString());
        assertTrue(r.accept(changed));
        assertFalse(r.permits(active));
        assertEquals(9, r.forWork().settings().outwardBatchCount());
    }

    @Test
    void staleSettingsPauseAndFreshSettingsResume() throws Exception {
        var clock = new AtomicLong();
        var r = new SettingsRegistry(100, clock::get);
        var s = QueueSettings.parse(fixture());
        r.accept(s);
        clock.set(101);
        assertNull(r.forWork());
        r.accept(s);
        assertNotNull(r.forWork());
    }

    @Test
    void disabledSettingsStopWork() throws Exception {
        var r = new SettingsRegistry(100, () -> 0);
        r.accept(QueueSettings.parse(new JSONObject(fixture()).put("enabled", false).toString()));
        assertNull(r.forWork());
    }

    @Test
    void oldRevisionOrChangedSameRevisionIsRejectedWithoutRefreshingLease() throws Exception {
        var clock = new AtomicLong();
        var r = new SettingsRegistry(100, clock::get);
        r.accept(QueueSettings.parse(new JSONObject(fixture()).put("revision", 2).toString()));
        clock.set(101);
        assertThrows(IllegalArgumentException.class, () -> r.accept(QueueSettings.parse(fixture())));
        assertThrows(IllegalArgumentException.class, () -> r.accept(QueueSettings.parse(
                new JSONObject(fixture()).put("revision", 2).put("port", 1415).toString())));
        assertNull(r.forWork());
    }

    @Test
    void invalidCountsFractionalNumbersAndUnboundedWaitAreRejected() throws Exception {
        String source = fixture();
        assertThrows(IllegalArgumentException.class,
                () -> QueueSettings.parse(new JSONObject(source).put("inward-batch-count", 0).toString()));
        assertThrows(ArithmeticException.class,
                () -> QueueSettings.parse(new JSONObject(source).put("outward-batch-count", 1.5).toString()));
        assertThrows(IllegalArgumentException.class,
                () -> QueueSettings.parse(new JSONObject(source).put("receive-timeout-ms", 6000).toString()));
        assertThrows(IllegalArgumentException.class,
                () -> QueueSettings.parse(new JSONObject(source).put("enabled", "true").toString()));
    }
}
