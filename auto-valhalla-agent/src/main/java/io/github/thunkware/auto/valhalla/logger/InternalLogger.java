package io.github.thunkware.auto.valhalla.logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Logging facade for the auto-valhalla agent. Example output:
 *
 * <pre>
 * 2024-01-15T10:23:45.123+01:00 WARN auto-valhalla.annotation.rejected - com.example.Point: not suitable
 * </pre>
 *
 * <p>Supported levels: {@code OFF}, {@code FATAL}, {@code ERROR}, {@code WARN},
 * {@code INFO}, {@code DEBUG}. {@code FATAL} logs at {@code WARN} and always throws.
 *
 * <p>Obtain a logger via {@link #getLogger(Class)} or {@link #getLogger(String)}. The
 * global level is set via {@link #setLevel(String)}; per-logger overrides via
 * {@link #setLevel(String, String)}. The logging mode is set via {@link #setMode(String)}.
 */
public final class InternalLogger {

    private record LogEntry(String name, Level level, String msg, Throwable throwable, ZonedDateTime timestamp) {

    }

    private static volatile Level level = Level.INFO;
    private static volatile LoggingMode loggingMode = LoggingMode.SIMPLE;
    private static final ConcurrentMap<String, Level> loggerLevels = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, InternalLogger> instances = new ConcurrentHashMap<>();

    private static volatile boolean buffering = false;
    private static final Queue<LogEntry> pendingLogs = new ConcurrentLinkedQueue<>();

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    private final String name;
    private volatile Object slf4jLogger;
    private volatile int slf4jVersion = -1;

    private InternalLogger(String name) {
        this.name = name;
    }

    public static InternalLogger getLogger(Class<?> cls) {
        return getLogger(cls.getName());
    }

    public static InternalLogger getLogger(String name) {
        return instances.computeIfAbsent(name, InternalLogger::new);
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
        if (loggerName == null || loggerName.isBlank()) {
            return;
        }
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
     * Starts buffering log output in memory. Flushed by {@link Slf4jBridge#reinstall()}.
     */
    public static void startBuffering() {
        buffering = true;
    }

    /**
     * Sets a per-logger level override only when no override is currently present.
     * Used to install defaults that user config (applied before calling this) takes precedence over.
     */
    public static void setLevelIfAbsent(String loggerName, Level level) {
        if (loggerName == null || loggerName.isBlank()) {
            return;
        }
        loggerLevels.putIfAbsent(loggerName, level);
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
        LoggingMode m = LoggingMode.findOrNull(s);
        if (m != null) {
            loggingMode = m;
        } else {
            loggingMode = LoggingMode.SIMPLE;
            getLogger(InternalLogger.class).warning("Unknown logging mode '" + s.trim() + "'; valid values are: "
                    + "simple, none, application. Defaulting to simple.");
        }
    }

    /**
     * Returns true when this logger's effective level is {@link Level#FATAL},
     * meaning failures should surface loudly (reject the class) rather than log-and-continue.
     */
    public boolean isFatal() {
        return loggerLevels.getOrDefault(name, level) == Level.FATAL;
    }

    /**
     * Logs at this logger's effective level. Does nothing when effective level is {@code OFF} or {@code FATAL}.
     */
    public void logAtEffectiveLevel(String msg) {
        logAtEffectiveLevel(msg, null);
    }

    /**
     * Logs at this logger's effective level, with a throwable. Does nothing when level is {@code OFF} or {@code FATAL}.
     */
    public void logAtEffectiveLevel(String msg, Throwable t) {
        Level eff = loggerLevels.getOrDefault(name, level);
        if (eff == Level.OFF || eff == Level.FATAL) {
            return;
        }
        log(eff, msg, t);
    }

    public boolean isDebugEnabled() {
        Level myLevel = loggerLevels.getOrDefault(name, level);
        return myLevel.rank >= Level.DEBUG.rank;
    }

    public String getName() {
        return name;
    }

    public void debug(String msg) {
        log(Level.DEBUG, msg, null);
    }

    public void debug(String msg, Throwable t) {
        log(Level.DEBUG, msg, t);
    }

    public void info(String msg) {
        log(Level.INFO, msg, null);
    }

    public void warning(String msg) {
        log(Level.WARN, msg, null);
    }

    public void error(String msg) {
        log(Level.ERROR, msg, null);
    }

    public void error(String msg, Throwable t) {
        log(Level.ERROR, msg, t);
    }

    /**
     * Logs at WARN level and always throws. If {@code throwable} is a
     * {@link RuntimeException} it is rethrown as-is; otherwise it is wrapped in
     * a new {@code RuntimeException}. Returns {@code RuntimeException} so callers
     * can write {@code throw log.fatal(…)} to satisfy the compiler's control-flow
     * analysis — the method never returns normally.
     */
    public RuntimeException fatal(String msg, Throwable t) {
        log(Level.FATAL, msg, t);
        if (t instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(msg, t);
    }

    /**
     * Like {@link #fatal(String, Throwable)} but with no cause. Always throws.
     */
    public RuntimeException fatal(String msg) {
        log(Level.FATAL, msg, null);
        throw new RuntimeException(msg);
    }

    private void log(Level lv, String msg, Throwable t) {
        Level effective = loggerLevels.getOrDefault(name, level);
        if (lv.rank > effective.rank) {
            return;
        }
        if (buffering) {
            pendingLogs.add(new LogEntry(name, lv, msg, t, ZonedDateTime.now()));
            return;
        }
        logDirect(lv, msg, t);
    }

    private void logDirect(Level lv, String msg, Throwable t) {
        logDirect(lv, msg, t, null);
    }

    private void logDirect(Level lv, String msg, Throwable t, ZonedDateTime timestamp) {
        switch (loggingMode) {
            case NONE -> {
            }
            case APPLICATION -> {
                if (logViaSlf4j(lv, msg, t)) {
                    return;
                }
                logToStderr(lv, msg, t, timestamp); // SLF4J not yet available; fall through to stderr
            }
            default -> logToStderr(lv, msg, t, timestamp);
        }
    }

    private static void flushBuffer() {
        buffering = false;
        LogEntry entry;
        while ((entry = pendingLogs.poll()) != null) {
            InternalLogger logger = getLogger(entry.name());
            logger.logDirect(entry.level(), entry.msg(), entry.throwable(), entry.timestamp());
        }
    }

    private boolean logViaSlf4j(Level lv, String msg, Throwable t) {
        int v = Slf4jBridge.version;
        if (slf4jVersion != v) {
            slf4jLogger = Slf4jBridge.getLoggerInstance(name);
            slf4jVersion = v;
        }
        if (slf4jLogger == null) {
            return false;
        }
        return Slf4jBridge.invoke(lv, slf4jLogger, msg, t);
    }

    private void logToStderr(Level lv, String msg, Throwable t, ZonedDateTime timestamp) {
        String ts = (timestamp != null ? timestamp : ZonedDateTime.now()).format(TIMESTAMP_FORMAT);
        String displayLevel = lv == Level.FATAL ? Level.WARN.name() : lv.name();
        System.err.println(ts + " " + displayLevel + " " + name + " - " + msg);
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
            if (!attempted) {
                init();
            }
            if (getLoggerMethod == null) {
                return null;
            }
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
                } else if ((lv == Level.FATAL || lv == Level.WARN) && t != null) {
                    h = warnWithCauseHandle;
                    withCause = true;
                } else {
                    h = switch (lv) {
                        case FATAL, WARN -> warnHandle;
                        case ERROR -> errorHandle;
                        case INFO -> infoHandle;
                        case DEBUG -> debugHandle;
                        default -> null;
                    };
                    withCause = false;
                }
                if (h == null) {
                    return false;
                }
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
            InternalLogger.flushBuffer();
        }

        private static synchronized void init() {
            if (attempted) {
                return;
            }
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) {
                    cl = ClassLoader.getSystemClassLoader();
                }
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
