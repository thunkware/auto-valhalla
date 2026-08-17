package io.github.thunkware.auto.valhalla.logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises {@link ApplicationLogger} with a real SLF4J binding (slf4j-simple)
 *  on the test classpath: messages must reach the SLF4J implementation rather
 *  than the stderr fallback. */
class ApplicationLoggerTest {

    private PrintStream originalErr;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() {
        originalErr = System.err;
        captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        InternalLoggerFactory.setSystem(LoggingSystem.APPLICATION.name());
        InternalLoggerFactory.reinstall();
    }

    @AfterEach
    void tearDown() {
        InternalLoggerFactory.setSystem(LoggingSystem.SIMPLE.name());
        System.setErr(originalErr);
    }

    @Test
    void delegatesToSlf4j() {
        InternalLoggerFactory.setLevel("test.logger", Level.DEBUG.name());
        InternalLogger log = InternalLoggerFactory.getLogger("test.logger");
        log.info("hello from ApplicationLogger");
        InternalLoggerFactory.reinstall();
        assertTrue(captured.toString().contains("hello from ApplicationLogger"),
                "the message must reach slf4j-simple, not the stderr fallback: " + captured);
    }

    @Test
    void slf4jIsDebugEnabledIsQueried() {
        InternalLoggerFactory.setLevel("test.debug-logger", Level.DEBUG.name());
        InternalLogger log = InternalLoggerFactory.getLogger("test.debug-logger");
        assertTrue(log.isDebugEnabled(),
                "with a DEBUG level, the underlying SLF4J logger must report debug enabled");
    }

    @Test
    void errorWithCauseDelegatesToSlf4j() {
        InternalLoggerFactory.setLevel("test.error-logger", Level.ERROR.name());
        InternalLogger log = InternalLoggerFactory.getLogger("test.error-logger");
        log.error("boom", new IllegalStateException("cause"));
        InternalLoggerFactory.reinstall();
        String out = captured.toString();
        assertTrue(out.contains("boom"), "message must be logged via SLF4J: " + out);
        assertTrue(out.contains("cause"), "the throwable stack trace must include the cause: " + out);
    }
}
