package io.github.thunkware.auto.valhalla.util;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The agent's background loops stop on interruption only if swallowing an
 *  {@link InterruptedException} leaves the interrupt flag set. */
class FailableTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void runQuietlyKeepsTheThreadInterrupted() {
        Failable.runQuietly(() -> {
            throw new InterruptedException("stop");
        });
        assertTrue(Thread.currentThread().isInterrupted(),
                "the interrupt flag must survive a swallowed InterruptedException");
    }

    @Test
    void callQuietlyKeepsTheThreadInterrupted() {
        assertNull(Failable.callQuietly(() -> {
            throw new InterruptedException("stop");
        }));
        assertTrue(Thread.currentThread().isInterrupted(),
                "the interrupt flag must survive a swallowed InterruptedException");
    }

    @Test
    void otherFailuresDoNotInterruptTheThread() {
        Failable.runQuietly(() -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(!Thread.currentThread().isInterrupted(),
                "only InterruptedException sets the flag");
    }
}
