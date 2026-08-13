package io.github.thunkware.auto.valhalla;

/**
 * Logging facade for the auto-valhalla agent. Supports log levels: {@code OFF},
 * {@code ERROR}, {@code WARNING}, {@code INFO}, {@code DEBUG}. Messages are
 * prefixed with {@code [auto-valhalla]} and the log level.
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
        } catch (IllegalArgumentException ignored) {
            level = Level.INFO;
        }
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
            System.err.println("[auto-valhalla] [" + lv + "] " + msg);
        }
    }
}
