package io.github.thunkware.auto.valhalla.logger;

import java.time.ZonedDateTime;

/**
 * The default {@link InternalLogger} implementation: writes to stderr and
 * resolves its effective level hierarchically via
 * {@link InternalLoggerFactory#effectiveLevel(String)}. In
 * {@link LoggingSystem#APPLICATION} mode it delegates to a
 * {@link ApplicationLogger} so agent messages flow through the application's
 * logging framework.
 */
final class SimpleLogger extends AbstractInternalLogger {

    private volatile ApplicationLogger slf4j;

    SimpleLogger(String name) {
        super(name);
    }

    @Override
    public boolean isDebugEnabled() {
        return InternalLoggerFactory.effectiveLevel(name).rank >= Level.DEBUG.rank;
    }

    @Override
    void logDirect(Level lv, String msg, Throwable t, ZonedDateTime timestamp) {
        switch (InternalLoggerFactory.system()) {
            case NONE -> {
            }
            case APPLICATION -> {
                if (slf4j == null) {
                    slf4j = new ApplicationLogger(name);
                }
                slf4j.logDirect(lv, msg, t, timestamp);
            }
            default -> logToStderr(name, lv, msg, t, timestamp);
        }
    }
}
