package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class SynchronizationMonitorTest {

    @Test
    void methodNameMatches() {
        assertDoesNotThrow(() -> SynchronizationMonitor.class.getMethod(SynchronizationMonitor.ON_SYNCHRONIZED, Object.class));
    }

}
