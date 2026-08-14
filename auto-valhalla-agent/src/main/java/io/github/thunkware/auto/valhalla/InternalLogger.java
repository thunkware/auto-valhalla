package io.github.thunkware.auto.valhalla;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logging facade for the auto-valhalla agent. Supports log levels: {@code OFF},
 * {@code ERROR}, {@code WARNING}, {@code INFO}, {@code DEBUG}. Messages are
 * prefixed with an ISO 8601 timestamp (with millisecond precision and timezone
 * offset), {@code [auto-valhalla]}, and the log level.
 *
 * <p>The global log level is set once at startup via {@link #setLevel(String)}.
 */
public final class InternalLogger {

    enum Level {
        OFF(0), ERROR(1), WARNING(2), INFO(3), DEBUG(4);

        final int rank;
        Level(int rank) { this.rank = rank; }
    }

    private static volatile Level level = Level.INFO;
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

    public static boolean isDebugEnabled() {
        return level.rank >= Level.DEBUG.rank;
    }

    public static void debug(String msg) {
        log(Level.DEBUG, msg);
    }

    public static void info(String msg) {
        log(Level.INFO, msg);
    }

    public static void warning(String msg) {
        log(Level.WARNING, msg);
    }

    public static void error(String msg) {
        log(Level.ERROR, msg);
    }

    public static void error(String msg, Throwable t) {
        log(Level.ERROR, msg);
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    private static void log(Level lv, String msg) {
        if (lv.rank <= level.rank) {
            String timestamp = ZonedDateTime.now().format(TIMESTAMP_FORMAT);
            System.err.println(timestamp + " [auto-valhalla] [" + lv + "] " + msg);
        }
    }
}
