package io.github.thunkware.auto.valhalla.logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exercises level-string parsing in {@link InternalLoggerFactory}, in
 *  particular the {@code warning} alias for {@link Level#WARN}. */
class InternalLoggerFactoryTest {

    @AfterEach
    void tearDown() {
        InternalLoggerFactory.setLevel(null);
        InternalLoggerFactory.setLevel("root", null);
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
}
