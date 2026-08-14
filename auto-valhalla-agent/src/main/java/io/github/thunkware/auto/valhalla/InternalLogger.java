package io.github.thunkware.auto.valhalla;

import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logging facade for the auto-valhalla agent. Supports log levels: {@code OFF},
 * {@code ERROR}, {@code WARNING}, {@code INFO}, {@code DEBUG}. Messages are
 * prefixed with an ISO 8601 timestamp (with millisecond precision and timezone
 * offset), {@code [auto-valhalla]}, and the log level.
 *
 * <p>The global log level is set once at startup via {@link #setLevel(String)}.
 * The logging mode is set via {@link #setMode(String)}.
 */
public final class InternalLogger {

    enum Level {
        OFF(0), ERROR(1), WARNING(2), INFO(3), DEBUG(4);

        final int rank;
        Level(int rank) { this.rank = rank; }
    }

    enum LoggingMode { SIMPLE, NONE, APPLICATION }

    private static volatile Level level = Level.INFO;
    private static volatile LoggingMode loggingMode = LoggingMode.SIMPLE;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    private InternalLogger() {}

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
            warning("Unknown log-level '" + s.trim() + "'; valid values are: "
                    + "off, error, warning, info, debug. Defaulting to info.");
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
                warning("Unknown logging mode '" + s.trim() + "'; valid values are: "
                        + "simple, none, application. Defaulting to simple.");
            }
        }
    }

    public static boolean isDebugEnabled() {
        return level.rank >= Level.DEBUG.rank;
    }

    public static void debug(String msg) {
        log(Level.DEBUG, msg, null);
    }

    public static void info(String msg) {
        log(Level.INFO, msg, null);
    }

    public static void warning(String msg) {
        log(Level.WARNING, msg, null);
    }

    public static void error(String msg) {
        log(Level.ERROR, msg, null);
    }

    public static void error(String msg, Throwable t) {
        log(Level.ERROR, msg, t);
    }

    private static void log(Level lv, String msg, Throwable t) {
        if (lv.rank > level.rank) return;
        switch (loggingMode) {
            case NONE -> {}
            case APPLICATION -> {
                if (Slf4jBridge.log(lv, msg, t)) return;
                logToStderr(lv, msg, t); // SLF4J not yet available; fall through to stderr
            }
            default -> logToStderr(lv, msg, t);
        }
    }

    private static void logToStderr(Level lv, String msg, Throwable t) {
        String timestamp = ZonedDateTime.now().format(TIMESTAMP_FORMAT);
        System.err.println(timestamp + " [auto-valhalla] [" + lv + "] " + msg);
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    /**
     * Lazily discovered SLF4J bridge. Resolved on the first log call in
     * {@link LoggingMode#APPLICATION} mode via reflection from the thread's
     * context classloader. If SLF4J is not available, {@link #log} returns
     * {@code false} and the caller falls back to stderr.
     */
    private static final class Slf4jBridge {

        private static volatile boolean attempted;
        private static volatile Object logger;
        private static volatile Method warnMethod;
        private static volatile Method errorMethod;
        private static volatile Method errorWithCauseMethod;
        private static volatile Method infoMethod;
        private static volatile Method debugMethod;

        static boolean log(Level lv, String msg, Throwable t) {
            if (!attempted) init();
            if (logger == null) return false;
            try {
                Method m;
                Object[] args;
                if (lv == Level.ERROR && t != null) {
                    m = errorWithCauseMethod;
                    args = new Object[] { msg, t };
                } else {
                    m = switch (lv) {
                        case ERROR -> errorMethod;
                        case WARNING -> warnMethod;
                        case INFO -> infoMethod;
                        case DEBUG -> debugMethod;
                        default -> null;
                    };
                    args = new Object[] { msg };
                }
                if (m == null) return false;
                m.invoke(logger, args);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private static synchronized void init() {
            if (attempted) return;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) cl = ClassLoader.getSystemClassLoader();
                Class<?> factory = Class.forName("org.slf4j.LoggerFactory", true, cl);
                Class<?> loggerIface = Class.forName("org.slf4j.Logger", true, cl);
                Object instance = factory.getMethod("getLogger", String.class)
                        .invoke(null, "io.github.thunkware.auto.valhalla");
                warnMethod = loggerIface.getMethod("warn", String.class);
                errorMethod = loggerIface.getMethod("error", String.class);
                errorWithCauseMethod = loggerIface.getMethod("error", String.class, Throwable.class);
                infoMethod = loggerIface.getMethod("info", String.class);
                debugMethod = loggerIface.getMethod("debug", String.class);
                logger = instance; // assign last: signals successful init
            } catch (Exception e) {
                // SLF4J not available; caller falls back to stderr
            } finally {
                attempted = true;
            }
        }
    }
}
