package io.github.thunkware.auto.valhalla.logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logging facade for the auto-valhalla agent. Supports log levels: {@code OFF},
 * {@code FATAL}, {@code ERROR}, {@code WARNING}, {@code INFO}, {@code DEBUG}. Messages
 * are prefixed with an ISO 8601 timestamp (with millisecond precision and timezone
 * offset), the log level, and the logger name (the fully-qualified class name of
 * the class that created the logger).
 *
 * <p>Obtain a logger per class via {@link #getLogger(Class)} and store it in an
 * instance field. The global log level is set once at startup via
 * {@link #setLevel(String)}. The logging mode is set via {@link #setMode(String)}.
 */
public final class InternalLogger {

    enum Level {
        OFF(0), FATAL(1), ERROR(2), WARNING(3), INFO(4), DEBUG(5);

        final int rank;
        Level(int rank) { this.rank = rank; }
    }

    enum LoggingMode { SIMPLE, NONE, APPLICATION }

    private static volatile Level level = Level.INFO;
    private static volatile LoggingMode loggingMode = LoggingMode.SIMPLE;
    private static final java.util.concurrent.ConcurrentHashMap<String, Level> loggerLevels =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    private final String name;
    private volatile Object slf4jLogger;
    private volatile int slf4jVersion = -1;

    private InternalLogger(String name) {
        this.name = name;
    }

    public static InternalLogger getLogger(Class<?> cls) {
        return new InternalLogger(cls.getName());
    }

    public static InternalLogger getLogger(String name) {
        return new InternalLogger(name);
    }

    /**
     * Sets the global log level from a string (case-insensitive):
     * {@code off}, {@code error}, {@code warning}, {@code info}, {@code debug}.
     * Unknown values default to {@code INFO}.
     */
    public static void setLevel(String s) {
        if (s == null || s.isBlank()) {
            level = Level.INFO;
            return;
        }
        try {
            level = Level.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            level = Level.INFO;
            getLogger(InternalLogger.class).warning("Unknown log-level '" + s.trim() + "'; valid values are: "
                    + "off, fatal, error, warning, info, debug. Defaulting to info.");
        }
    }

    /**
     * Sets a per-logger level override for {@code loggerName}. Overrides the
     * global level set by {@link #setLevel(String)} for that specific logger only.
     * Pass a {@code null} or blank level to remove an existing override.
     */
    public static void setLevel(String loggerName, String levelString) {
        if (loggerName == null || loggerName.isBlank()) return;
        if (levelString == null || levelString.isBlank()) {
            loggerLevels.remove(loggerName);
            return;
        }
        try {
            loggerLevels.put(loggerName, Level.valueOf(levelString.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            getLogger(InternalLogger.class).warning(
                    "Unknown log-level '" + levelString.trim() + "' for logger '"
                    + loggerName + "'; valid values are: off, fatal, error, warning, info, debug. Ignoring.");
        }
    }

    /**
     * Sets the logging mode from a string (case-insensitive):
     * {@code simple} (default), {@code none}, {@code application}.
     * Unknown values default to {@code simple}.
     */
    public static void setMode(String s) {
        if (s == null || s.isBlank()) {
            loggingMode = LoggingMode.SIMPLE;
            return;
        }
        switch (s.trim().toLowerCase()) {
            case "simple" -> loggingMode = LoggingMode.SIMPLE;
            case "none" -> loggingMode = LoggingMode.NONE;
            case "application" -> loggingMode = LoggingMode.APPLICATION;
            default -> {
                loggingMode = LoggingMode.SIMPLE;
                getLogger(InternalLogger.class).warning("Unknown logging mode '" + s.trim() + "'; valid values are: "
                        + "simple, none, application. Defaulting to simple.");
            }
        }
    }

    public static boolean isDebugEnabled() {
        return level.rank >= Level.DEBUG.rank;
    }

    public void debug(String msg) {
        log(Level.DEBUG, msg, null);
    }

    public void info(String msg) {
        log(Level.INFO, msg, null);
    }

    public void warning(String msg) {
        log(Level.WARNING, msg, null);
    }

    public void error(String msg) {
        log(Level.ERROR, msg, null);
    }

    public void error(String msg, Throwable t) {
        log(Level.ERROR, msg, t);
    }

    /**
     * Logs at WARNING level and always throws. If {@code t} is a
     * {@link RuntimeException} it is rethrown as-is; otherwise it is wrapped in
     * a new {@code RuntimeException}. Returns {@code RuntimeException} so callers
     * can write {@code throw log.fatal(…)} to satisfy the compiler's control-flow
     * analysis — the method never returns normally.
     */
    public RuntimeException fatal(String msg, Throwable t) {
        log(Level.FATAL, msg, t);
        if (t instanceof RuntimeException re) throw re;
        throw new RuntimeException(msg, t);
    }

    /** Like {@link #fatal(String, Throwable)} but with no cause. Always throws. */
    public RuntimeException fatal(String msg) {
        log(Level.FATAL, msg, null);
        throw new RuntimeException(msg);
    }

    private void log(Level lv, String msg, Throwable t) {
        Level effective = loggerLevels.getOrDefault(name, level);
        if (lv.rank > effective.rank) return;
        switch (loggingMode) {
            case NONE -> {}
            case APPLICATION -> {
                if (logViaSlf4j(lv, msg, t)) return;
                logToStderr(lv, msg, t); // SLF4J not yet available; fall through to stderr
            }
            default -> logToStderr(lv, msg, t);
        }
    }

    private boolean logViaSlf4j(Level lv, String msg, Throwable t) {
        int v = Slf4jBridge.version;
        if (slf4jVersion != v) {
            slf4jLogger = Slf4jBridge.getLoggerInstance(name);
            slf4jVersion = v;
        }
        if (slf4jLogger == null) return false;
        return Slf4jBridge.invoke(lv, slf4jLogger, msg, t);
    }

    private void logToStderr(Level lv, String msg, Throwable t) {
        String timestamp = ZonedDateTime.now().format(TIMESTAMP_FORMAT);
        String displayLevel = lv == Level.FATAL ? Level.WARNING.name() : lv.name();
        System.err.println(timestamp + " " + displayLevel + " " + name + " - " + msg);
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    /**
     * Lazily discovered SLF4J bridge. Resolved on the first log call in
     * {@link LoggingMode#APPLICATION} mode via reflection from the thread's
     * context classloader. If SLF4J is not available, {@link #logViaSlf4j} returns
     * {@code false} and the caller falls back to stderr.
     *
     * <p>{@link #reinstall()} is called by {@link ApplicationLoggerFlags} once
     * the application's logging framework is confirmed ready (via bytecode
     * instrumentation of SLF4J / Spring Boot classes), so early startup messages
     * do not permanently lock in a NOP or substitute logger. On reinstall, the
     * global {@link #version} counter is incremented, causing each
     * {@link InternalLogger} instance to lazily re-acquire its SLF4J logger on the
     * next log call.
     */
    static final class Slf4jBridge {

        static volatile int version = 0;
        private static volatile boolean attempted;
        private static volatile Method getLoggerMethod;
        private static volatile MethodHandle warnHandle;
        private static volatile MethodHandle warnWithCauseHandle;
        private static volatile MethodHandle errorHandle;
        private static volatile MethodHandle errorWithCauseHandle;
        private static volatile MethodHandle infoHandle;
        private static volatile MethodHandle debugHandle;

        static Object getLoggerInstance(String name) {
            if (!attempted) init();
            if (getLoggerMethod == null) return null;
            try {
                return getLoggerMethod.invoke(null, name);
            } catch (Throwable e) {
                return null;
            }
        }

        static boolean invoke(Level lv, Object logger, String msg, Throwable t) {
            try {
                MethodHandle h;
                boolean withCause;
                if (lv == Level.ERROR && t != null) {
                    h = errorWithCauseHandle;
                    withCause = true;
                } else if ((lv == Level.FATAL || lv == Level.WARNING) && t != null) {
                    h = warnWithCauseHandle;
                    withCause = true;
                } else {
                    h = switch (lv) {
                        case FATAL, WARNING -> warnHandle;
                        case ERROR -> errorHandle;
                        case INFO -> infoHandle;
                        case DEBUG -> debugHandle;
                        default -> null;
                    };
                    withCause = false;
                }
                if (h == null) return false;
                if (withCause) {
                    h.invokeWithArguments(logger, msg, t);
                } else {
                    h.invokeWithArguments(logger, msg);
                }
                return true;
            } catch (Throwable e) {
                return false;
            }
        }

        /**
         * Resets all cached SLF4J state and immediately re-initializes from the
         * current thread context classloader. Called when bytecode instrumentation
         * detects that the application's logging framework is ready.
         */
        static synchronized void reinstall() {
            getLoggerMethod = null;
            warnHandle = null;
            warnWithCauseHandle = null;
            errorHandle = null;
            errorWithCauseHandle = null;
            infoHandle = null;
            debugHandle = null;
            attempted = false;
            version++; // triggers lazy re-acquire in all InternalLogger instances
            init();    // reentrant: same thread holds the lock
        }

        private static synchronized void init() {
            if (attempted) return;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) cl = ClassLoader.getSystemClassLoader();
                Class<?> factory = Class.forName("org.slf4j.LoggerFactory", false, cl);
                getLoggerMethod = factory.getMethod("getLogger", String.class);
                // Obtain a probe logger and discover methods via MethodHandles.findVirtual().
                // findVirtual() resolves by name+descriptor without calling getDeclaredMethods0(),
                // so org.slf4j.event.Level is never loaded through LaunchedClassLoader's
                // JarUrlClassLoader — which would throw a duplicate class definition LinkageError
                // in Spring Boot (JarUrlClassLoader.findLoadedClass() does not see classes already
                // defined in LaunchedClassLoader, causing it to attempt defineClass() again).
                Object probe = getLoggerMethod.invoke(null, "io.github.thunkware.auto.valhalla");
                Class<?> cls = probe.getClass();
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                MethodType str = MethodType.methodType(void.class, String.class);
                MethodType strCause = MethodType.methodType(void.class, String.class, Throwable.class);
                warnHandle = lookup.findVirtual(cls, "warn", str);
                warnWithCauseHandle = lookup.findVirtual(cls, "warn", strCause);
                errorHandle = lookup.findVirtual(cls, "error", str);
                errorWithCauseHandle = lookup.findVirtual(cls, "error", strCause);
                infoHandle = lookup.findVirtual(cls, "info", str);
                debugHandle = lookup.findVirtual(cls, "debug", str);
            } catch (Throwable e) {
                // SLF4J not available or classloader conflict; caller falls back to stderr
            } finally {
                attempted = true;
            }
        }
    }
}
