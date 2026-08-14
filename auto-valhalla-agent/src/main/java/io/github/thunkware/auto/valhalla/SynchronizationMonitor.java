package io.github.thunkware.auto.valhalla;

/**
 * Monitors synchronization attempts in instrumented classes. Called before each
 * {@code monitorenter} instruction when {@code Mode.SYNCHRONIZATION_MONITOR} is
 * enabled.
 *
 * <p>Logs every synchronization attempt at the configured level. Optionally
 * records each first-seen class name to a file via {@link BackgroundFileWriter}
 * when {@code synchronization-monitor.append-to} is configured.
 *
 * <p>This class must be loadable from the instrumented application classes (it
 * lives in the agent's own package, which is never transformed) and must never
 * throw, so every operation is defensive.
 */
public final class SynchronizationMonitor {

    private static volatile BackgroundFileWriter writer;
    private static volatile OnSuccess logLevel = OnSuccess.INFO;
    private static volatile boolean active = false;

    private SynchronizationMonitor() {}

    /** Activates monitoring, sets the log level, and optionally enables
     *  file recording to {@code path} (may be {@code null} or empty). */
    public static void configure(String path, OnSuccess level) {
        logLevel = level;
        active = true;
        if (path != null && !path.isEmpty()) {
            writer = BackgroundFileWriter.forFile(path);
        }
    }

    /** Called immediately before each {@code monitorenter} with the object being
     *  locked. Logs the event at the configured level; also appends the class
     *  name to the file (deduplicated) if one is configured. */
    public static void check(Object o) {
        if (o == null || !active) {
            return;
        }
        // the monitor must never affect the synchronized block
        Failable.runQuietly(() -> {
            String name = o.getClass().getName();
            BackgroundFileWriter localWriter = writer;
            if (localWriter != null) {
                localWriter.record(name);
            }
            String msg = "Synchronized on: " + name;
            if (logLevel == OnSuccess.DEBUG) {
                InternalLogger.debug(msg);
            } else {
                InternalLogger.info(msg);
            }
        });
    }
}
