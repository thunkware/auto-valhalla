package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SynchronizationMonitorTest {

    @Test
    void methodNameMatches() {
        assertDoesNotThrow(() -> SynchronizationMonitor.class.getMethod(SynchronizationMonitor.ON_SYNCHRONIZED, Object.class));
    }

    @Test
    void onSynchronizedRecordsStats() {
        long before = Stats.synchronizedCount();
        SynchronizationMonitor.onSynchronized(new Object());
        assertEquals(before + 1, Stats.synchronizedCount(),
                "onSynchronized() must record one synchronized event via Stats.onSynchronized");
    }

    @Test
    void onSynchronizedNullRecordsNothing() {
        long before = Stats.synchronizedCount();
        SynchronizationMonitor.onSynchronized(null);
        assertEquals(before, Stats.synchronizedCount(),
                "onSynchronized(null) must not record a synchronized event");
    }

    @Test
    void onSynchronizedCountsEveryCallEvenForSameClass() {
        long before = Stats.synchronizedCount();
        for (int i = 0; i < 3; i++) {
            SynchronizationMonitor.onSynchronized(new Object());
        }
        assertEquals(before + 3, Stats.synchronizedCount(),
                "each onSynchronized() call must be recorded regardless of log dedup");
    }
}
