package io.github.thunkware.auto.valhalla.logger;

import java.time.ZonedDateTime;

/**
 * Shared implementation of the level-checking, buffering, and {@code fatal} /
 * {@code logAtEffectiveLevel} behavior common to every {@link InternalLogger}.
 * Concrete implementations only provide {@link #logDirect} (the actual sink) and
 * {@link #isDebugEnabled}.
 */
abstract class AbstractInternalLogger implements InternalLogger {

    protected final String name;

    AbstractInternalLogger(String name) {
        this.name = name;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final boolean isFatal() {
        return InternalLoggerFactory.effectiveLevel(name) == Level.FATAL;
    }

    @Override
    public final void debug(String msg) {
        log(Level.DEBUG, msg, null);
    }

    @Override
    public final void debug(String msg, Throwable t) {
        log(Level.DEBUG, msg, t);
    }

    @Override
    public final void info(String msg) {
        log(Level.INFO, msg, null);
    }

    @Override
    public final void warning(String msg) {
        log(Level.WARN, msg, null);
    }

    @Override
    public final void error(String msg) {
        log(Level.ERROR, msg, null);
    }

    @Override
    public final void error(String msg, Throwable t) {
        log(Level.ERROR, msg, t);
    }

    @Override
    public final RuntimeException fatal(String msg, Throwable t) {
        log(Level.FATAL, msg, t);
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(msg, t);
    }

    @Override
    public final RuntimeException fatal(String msg) {
        log(Level.FATAL, msg, null);
        throw new RuntimeException(msg);
    }

    @Override
    public final void logAtEffectiveLevel(String msg) {
        logAtEffectiveLevel(msg, null);
    }

    @Override
    public final void logAtEffectiveLevel(String msg, Throwable t) {
        Level effective = InternalLoggerFactory.effectiveLevel(name);
        if (effective == Level.OFF || effective == Level.FATAL) {
            return;
        }
        log(effective, msg, t);
    }

    private void log(Level lv, String msg, Throwable t) {
        if (InternalLoggerFactory.mode() == LoggingMode.NONE) {
            return;
        }
        Level effective = InternalLoggerFactory.effectiveLevel(name);
        if (lv.rank > effective.rank) {
            return;
        }
        if (InternalLoggerFactory.isBuffering()) {
            InternalLoggerFactory.buffer(
                    new InternalLoggerFactory.LogEntry(name, lv, msg, t, ZonedDateTime.now()));
            return;
        }
        logDirect(lv, msg, t, null);
    }

    abstract void logDirect(Level lv, String msg, Throwable t, ZonedDateTime timestamp);

    /** Writes a single message to stderr. Used by the simple sink and as the
     *  fallback when SLF4J is not yet available. */
    static void logToStderr(String name, Level lv, String msg, Throwable t,
            ZonedDateTime timestamp) {
        String displayLevel = lv == Level.FATAL ? Level.WARN.name() : lv.name();
        System.err.println(InternalLoggerFactory.timestamp(timestamp) + " " + displayLevel
                + " " + name + " - " + msg);
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }
}
