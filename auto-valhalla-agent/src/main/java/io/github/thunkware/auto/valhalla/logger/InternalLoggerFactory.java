package io.github.thunkware.auto.valhalla.logger;

import io.github.thunkware.auto.valhalla.util.StringUtils;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates {@link InternalLogger} instances and holds the shared logging
 * configuration (global level, per-logger overrides, and logging system).
 *
 * <p>A logger's effective level is resolved hierarchically: the exact name, then
 * each ancestor package, then {@code root}, then the global default. This lets a
 * single level override silence or raise a whole package subtree.
 */
public final class InternalLoggerFactory {

    record LogEntry(String name, Level level, String msg, Throwable throwable, ZonedDateTime timestamp) {

    }

    private static volatile Level level = Level.INFO;
    private static volatile LoggingSystem loggingSystem = LoggingSystem.SIMPLE;
    private static final ConcurrentMap<String, Level> loggerLevels = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, InternalLogger> instances = new ConcurrentHashMap<>();

    private static final AtomicBoolean buffering = new AtomicBoolean(false);
    private static final Queue<LogEntry> pendingLogs = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pendingCount = new AtomicInteger();

    /** Undocumented safety valves for {@link #startBuffering()}: buffering is
     *  abandoned (and everything held is emitted) after this many messages or
     *  this many milliseconds, whichever comes first, so a bridge that never
     *  becomes ready cannot swallow the agent's output. A non-positive
     *  {@code maxBufferMillis} disables the time limit. Not final so tests can
     *  shrink them. */
    static int maxBufferedLogs =
            Integer.getInteger(InternalLoggerFactory.class.getName() + ".maxBufferedLogs", 1_000);
    static long maxBufferMillis =
            Long.getLong(InternalLoggerFactory.class.getName() + ".maxBufferMillis", 60_000L);

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
        // The system is typically set after loggers are first obtained (static
        // initializers run before AutoValhallaAgent28.install), so the default
        // implementation re-checks the system at log time; in APPLICATION mode it
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
            level = Level.find(s);
        } catch (IllegalArgumentException e) {
            level = Level.INFO;
            getLogger(InternalLoggerFactory.class).warning("Unknown log-level '" + s.trim()
                    + "'; valid values are: off, fatal, error, warning, info, debug, trace. Defaulting to info.");
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
            loggerLevels.put(loggerName, Level.find(levelString));
        } catch (IllegalArgumentException e) {
            getLogger(InternalLoggerFactory.class).warning(
                    "Unknown log-level '" + levelString.trim() + "' for logger '"
                            + loggerName + "'; valid values are: off, fatal, error, warning, info, debug, trace. Ignoring.");
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
     * Sets the logging system from a string (case-insensitive):
     * {@code simple} (default), {@code none}, {@code application}.
     * Unknown values default to {@code simple}.
     */
    public static void setSystem(String s) {
        if (s == null || s.isBlank()) {
            loggingSystem = LoggingSystem.SIMPLE;
            return;
        }
        LoggingSystem m = LoggingSystem.findOrNull(s);
        if (m != null) {
            loggingSystem = m;
        } else {
            loggingSystem = LoggingSystem.SIMPLE;
            getLogger(InternalLoggerFactory.class).warning("Unknown logging system '" + s.trim()
                    + "'; valid values are: simple, none, application. Defaulting to simple.");
        }
    }

    /**
     * Starts buffering log output in memory. Flushed by {@link #reinstall()}.
     * Buffering only engages in {@link LoggingSystem#APPLICATION} mode (where the
     * SLF4J bridge is installed and will eventually flush); in any other mode
     * this is a no-op so messages are never held and lost.
     *
     * <p>Buffering is bounded: it is abandoned after {@code maxBufferedLogs}
     * messages or {@code maxBufferMillis} (see the fields of the same name), so an
     * application that never reaches the point where the bridge is installed —
     * a Spring Boot fat jar started through a custom main, say — still gets the
     * agent's messages on stderr instead of losing them.
     */
    public static void startBuffering() {
        if (loggingSystem == LoggingSystem.APPLICATION && buffering.compareAndSet(false, true)) {
            startBufferDeadline();
        }
    }

    /** Abandons buffering once {@link #maxBufferMillis} has passed, unless it has
     *  already been flushed by then. */
    private static void startBufferDeadline() {
        if (maxBufferMillis <= 0) {
            return;
        }
        Thread.ofVirtual()
              .name("auto-valhalla-InternalLoggerFactory-BufferDeadline")
              .start(() -> {
                  try {
                      Thread.sleep(maxBufferMillis);
                  } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                  }
                  if (buffering.get()) {
                      flushBuffer();
                  }
              });
    }

    /**
     * Re-acquires the SLF4J logger bridge and flushes any buffered messages.
     * Called once the application's logging framework is ready.
     */
    public static void reinstall() {
        ApplicationLogger.reinstall();
        flushBuffer();
    }

    static LoggingSystem system() {
        return loggingSystem;
    }

    static boolean isBuffering() {
        return buffering.get();
    }

    static void buffer(LogEntry entry) {
        pendingLogs.add(entry);
        if (pendingCount.incrementAndGet() >= maxBufferedLogs) {
            // The bridge is taking too many messages to appear; give up on it and
            // emit what we have rather than growing without bound.
            flushBuffer();
        }
    }

    static void flushBuffer() {
        buffering.set(false);
        pendingCount.set(0);
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
