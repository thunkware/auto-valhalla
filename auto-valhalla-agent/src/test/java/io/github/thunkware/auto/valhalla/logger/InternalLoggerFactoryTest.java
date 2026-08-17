package io.github.thunkware.auto.valhalla.logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exercises level-string parsing in {@link InternalLoggerFactory}, in
 *  particular the {@code warning} alias for {@link Level#WARN}, and the bounds on
 *  in-memory buffering. */
class InternalLoggerFactoryTest {

    @AfterEach
    void tearDown() {
        InternalLoggerFactory.setLevel(null);
        InternalLoggerFactory.setLevel("root", null);
        InternalLoggerFactory.setSystem(null);
        InternalLoggerFactory.flushBuffer();
        InternalLoggerFactory.maxBufferedLogs = 1_000;
        InternalLoggerFactory.maxBufferMillis = 60_000L;
    }

    @Test
    void warningMapsToWarnForGlobalLevel() {
        InternalLoggerFactory.setLevel("warning");
        assertEquals(Level.WARN, InternalLoggerFactory.effectiveLevel("any.logger"));
    }

    @Test
    void warnMapsToWarnForGlobalLevel() {
        InternalLoggerFactory.setLevel("warn");
        assertEquals(Level.WARN, InternalLoggerFactory.effectiveLevel("any.logger"));
    }

    @Test
    void warningMapsToWarnForPerLoggerOverride() {
        InternalLoggerFactory.setLevel("test.logger", "warning");
        assertEquals(Level.WARN, InternalLoggerFactory.effectiveLevel("test.logger"));
    }

    @Test
    void bufferingIsAbandonedAfterTooManyMessages() {
        InternalLoggerFactory.maxBufferedLogs = 3;
        InternalLoggerFactory.setSystem("application");
        InternalLoggerFactory.startBuffering();
        assertTrue(InternalLoggerFactory.isBuffering(), "application mode buffers");

        for (int i = 0; i < 3; i++) {
            InternalLoggerFactory.buffer(new InternalLoggerFactory.LogEntry(
                    "test.logger", Level.INFO, "held " + i, null, ZonedDateTime.now()));
        }
        assertFalse(InternalLoggerFactory.isBuffering(),
                "the message cap must abandon buffering so nothing is held forever");
    }

    @Test
    void bufferingIsAbandonedAfterTheDeadline() throws Exception {
        InternalLoggerFactory.maxBufferMillis = 50L;
        InternalLoggerFactory.setSystem("application");
        InternalLoggerFactory.startBuffering();
        assertTrue(InternalLoggerFactory.isBuffering(), "application mode buffers");

        long deadline = System.currentTimeMillis() + 5_000;
        while (InternalLoggerFactory.isBuffering() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(InternalLoggerFactory.isBuffering(),
                "buffering must be abandoned once maxBufferMillis has passed");
    }

    @Test
    void bufferingIsANoOpOutsideApplicationMode() {
        InternalLoggerFactory.setSystem("simple");
        InternalLoggerFactory.startBuffering();
        assertFalse(InternalLoggerFactory.isBuffering(),
                "only application mode has a bridge that will flush");
    }
}
