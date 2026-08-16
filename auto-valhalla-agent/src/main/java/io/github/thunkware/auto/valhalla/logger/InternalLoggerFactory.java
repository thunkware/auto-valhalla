package io.github.thunkware.auto.valhalla.logger;

import io.github.thunkware.auto.valhalla.util.StringUtils;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates {@link InternalLogger} instances and holds the shared logging
 * configuration (global level, per-logger overrides, and logging mode).
 *
 * <p>A logger's effective level is resolved hierarchically: the exact name, then
 * each ancestor package, then {@code root}, then the global default. This lets a
 * single level override silence or raise a whole package subtree.
 */
public final class InternalLoggerFactory {

    record LogEntry(String name, Level level, String msg, Throwable throwable, ZonedDateTime timestamp) {

    }

    private static volatile Level level = Level.INFO;
    private static volatile LoggingMode loggingMode = LoggingMode.SIMPLE;
    private static final ConcurrentMap<String, Level> loggerLevels = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, InternalLogger> instances = new ConcurrentHashMap<>();

    private static volatile boolean buffering = false;
    private static final Queue<LogEntry> pendingLogs = new ConcurrentLinkedQueue<>();

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    private InternalLoggerFactory() {}

    public static InternalLogger getLogger(Class<?> cls) {
        return getLogger(cls.getName());
    }

    public static InternalLogger getLogger(String name) {
        return instances.computeIfAbsent(name, InternalLoggerFactory::create);
    }

    private static InternalLogger create(String name) {
        // The mode is typically set after loggers are first obtained (static
        // initializers run before AutoValhallaAgent.install), so the default
        // implementation re-checks the mode at log time; in APPLICATION mode it
        // delegates to a ApplicationLogger.
        return new SimpleLogger(name);
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
            getLogger(InternalLoggerFactory.class).warning("Unknown log-level '" + s.trim()
                    + "'; valid values are: off, fatal, error, warning, info, debug. Defaulting to info.");
        }
    }

    /**
     * Sets a per-logger level override for {@code loggerName}. Overrides the
     * global level for that logger and, because of hierarchical lookup, its
     * descendant namespaces. Pass a {@code null} or blank level to remove an
     * existing override.
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
            getLogger(InternalLoggerFactory.class).warning(
                    "Unknown log-level '" + levelString.trim() + "' for logger '"
                            + loggerName + "'; valid values are: off, fatal, error, warning, info, debug. Ignoring.");
        }
    }

    /**
     * Sets a per-logger level override only when no override is currently present.
     * Used to install defaults that user config (applied before calling this) takes precedence over.
     */
    public static void setLevelIfAbsent(InternalLogger logger, Level level) {
        loggerLevels.putIfAbsent(logger.getName(), level);
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
            getLogger(InternalLoggerFactory.class).warning("Unknown logging mode '" + s.trim()
                    + "'; valid values are: simple, none, application. Defaulting to simple.");
        }
    }

    /** Starts buffering log output in memory. Flushed by {@link #reinstall()}. */
    public static void startBuffering() {
        buffering = true;
    }

    /**
     * Re-acquires the SLF4J logger bridge and flushes any buffered messages.
     * Called once the application's logging framework is ready.
     */
    public static void reinstall() {
        ApplicationLogger.reinstall();
        flushBuffer();
    }

    static LoggingMode mode() {
        return loggingMode;
    }

    static boolean isBuffering() {
        return buffering;
    }

    static void buffer(LogEntry entry) {
        pendingLogs.add(entry);
    }

    static void flushBuffer() {
        buffering = false;
        LogEntry entry;
        while ((entry = pendingLogs.poll()) != null) {
            InternalLogger logger = getLogger(entry.name());
            ((AbstractInternalLogger) logger).logDirect(entry.level(), entry.msg(),
                    entry.throwable(), entry.timestamp());
        }
    }

    static String timestamp(ZonedDateTime timestamp) {
        final var now = timestamp != null ? timestamp : ZonedDateTime.now();
        return now.format(TIMESTAMP_FORMAT);
    }

    /**
     * The effective level of {@code name}: the most specific per-logger override,
     * walking up package levels to {@code root}, then the global default.
     */
    static Level effectiveLevel(String name) {
        String key = name;
        while (true) {
            Level lv = loggerLevels.get(key);
            if (lv != null) {
                return lv;
            }
            int dot = key.lastIndexOf('.');
            if (dot <= 0) {
                Level root = loggerLevels.get("root");
                return root != null ? root : level;
            }
            key = StringUtils.substringBeforeLast(key, ".");
        }
    }
}
