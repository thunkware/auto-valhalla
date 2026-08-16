package io.github.thunkware.auto.valhalla.logger;

import java.time.ZonedDateTime;

/**
 * The default {@link InternalLogger} implementation: writes to stderr and
 * resolves its effective level hierarchically via
 * {@link InternalLoggerFactory#effectiveLevel(String)}. In
 * {@link LoggingMode#APPLICATION} mode it delegates to a
 * {@link Slf4jInternalLogger} so agent messages flow through the application's
 * logging framework.
 */
final class SimpleInternalLogger extends AbstractInternalLogger {

    private volatile Slf4jInternalLogger slf4j;

    SimpleInternalLogger(String name) {
        super(name);
    }

    @Override
    public boolean isDebugEnabled() {
        return InternalLoggerFactory.effectiveLevel(name).rank >= Level.DEBUG.rank;
    }

    @Override
    void logDirect(Level lv, String msg, Throwable t, ZonedDateTime timestamp) {
        switch (InternalLoggerFactory.mode()) {
            case NONE -> {
            }
            case APPLICATION -> {
                if (slf4j == null) {
                    slf4j = new Slf4jInternalLogger(name);
                }
                slf4j.logDirect(lv, msg, t, timestamp);
            }
            default -> logToStderr(name, lv, msg, t, timestamp);
        }
    }
}
