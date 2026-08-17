package io.github.thunkware.auto.valhalla.logger;

/**
 * Logging facade for the auto-valhalla agent.
 *
 * <p>Obtain a logger via {@link InternalLoggerFactory#getLogger(Class)} or
 * {@link InternalLoggerFactory#getLogger(String)}. The global level, per-logger
 * overrides, and logging system are configured on {@link InternalLoggerFactory}.
 *
 * <p>Supported levels: {@code OFF}, {@code FATAL}, {@code ERROR}, {@code WARN},
 * {@code INFO}, {@code DEBUG}. {@code FATAL} logs at {@code WARN} and always throws.
 */
public interface InternalLogger {

    /** The logger name (typically a class name or a dotted category). */
    String getName();

    /** True when debug messages would be emitted for this logger. */
    boolean isDebugEnabled();

    /** True when this logger's effective level is {@link Level#FATAL}. */
    boolean isFatal();

    void debug(String msg);

    void debug(String msg, Throwable t);

    void info(String msg);

    void warning(String msg);

    void error(String msg);

    void error(String msg, Throwable t);

    /**
     * Logs at {@code WARN} and always throws. If {@code throwable} is a
     * {@link RuntimeException} it is rethrown as-is; otherwise it is wrapped in a
     * new {@code RuntimeException}. Returns {@code RuntimeException} so callers
     * can write {@code throw log.fatal(…)} to satisfy the compiler's control-flow
     * analysis — the method never returns normally.
     */
    RuntimeException fatal(String msg, Throwable t);

    /** Like {@link #fatal(String, Throwable)} but with no cause. Always throws. */
    RuntimeException fatal(String msg);

    /** Logs at this logger's effective level. Does nothing when the level is
     *  {@code OFF} or {@code FATAL}. */
    void logAtEffectiveLevel(String msg);

    /** Like {@link #logAtEffectiveLevel(String)} but with a throwable. */
    void logAtEffectiveLevel(String msg, Throwable t);
}
