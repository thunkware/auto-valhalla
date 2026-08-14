package io.github.thunkware.auto.valhalla;

/**
 * Monitors synchronization attempts in instrumented classes. Called before each
 * {@code monitorenter} instruction when {@code Mode.SYNCHRONIZATION_MONITOR} is
 * enabled and {@code auto-valhalla.synchronization-monitor.append-to} is configured.
 *
 * <p>Records the name of every class being synchronized on using
 * {@link AsyncFileWriter} for non-blocking, background-flushed I/O.
 *
 * <p>This class must be loadable from the instrumented application classes (it
 * lives in the agent's own package, which is never transformed) and must never
 * throw, so every operation is defensive.
 */
public final class SynchronizationMonitor {

    private static volatile AsyncFileWriter writer;

    private SynchronizationMonitor() {}

    /** Enables recording to {@code path}. The file is read once so names already
     *  present are not appended again. */
    public static void configure(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        writer = AsyncFileWriter.forFile(path);
    }

    /** Called immediately before each {@code monitorenter} with the object being
     *  locked. Records the class name for inspection. */
    public static void check(Object o) {
        if (o == null || writer == null) {
            return;
        }
        // the monitor must never affect the synchronized block
        Failable.runQuietly(() -> writer.record(o.getClass().getName()));
    }
}
