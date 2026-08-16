package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import io.github.thunkware.auto.valhalla.logger.InternalLoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors synchronization attempts in instrumented classes. Called before each
 * {@code monitorenter} instruction when {@code Mode.SYNCHRONIZATION_MONITOR} is
 * enabled.
 *
 * <p>Logs each first-seen class name (per JVM run) at the configured level.
 * Optionally also records class names to a file via {@link BackgroundFileWriter}
 * when {@code synchronization-monitor.append-to} is configured (file writes are
 * deduplicated against existing file contents; the log seen-set is always empty
 * at start-up regardless).
 *
 * <p>This class must be loadable from the instrumented application classes (it
 * lives in the agent's own package, which is never transformed) and must never
 * throw, so every operation is defensive.
 */
public final class SynchronizationMonitor {

    private static final InternalLogger SYNC_LOG =
            InternalLoggerFactory.getLogger("auto-valhalla.synchronization-monitor");
    private static volatile BackgroundFileWriter writer;
    private static volatile boolean active = false;
    private static final Set<String> logSeen = ConcurrentHashMap.newKeySet();

    private SynchronizationMonitor() {}

    /** Activates monitoring and optionally enables file recording to {@code path}
     *  (may be {@code null} or empty). Log verbosity is controlled via
     *  {@code logging.level.auto-valhalla.synchronization-monitor}. */
    public static void configure(String path) {
        active = true;
        if (path != null && !path.isEmpty()) {
            writer = BackgroundFileWriter.forFile(path);
        }
    }

    /** Called immediately before each {@code monitorenter} with the object being
     *  locked. Logs the first occurrence of each class per JVM run at the
     *  configured level; also appends to the file (deduplicated) if one is
     *  configured. */
    public static void check(Object o) {
        if (o == null || !active) {
            return;
        }
        long startTime = System.nanoTime();
        try {
            String name = o.getClass().getName();
            BackgroundFileWriter localWriter = writer;
            if (localWriter != null) {
                localWriter.record(name);
            }
            if (logSeen.add(name)) {
                SYNC_LOG.info("Synchronized on: " + name);
            }
        } catch (Throwable t) {
            // the monitor must never affect the synchronized block
            // don't even (debug) log the exception
        } finally {
            long duration = System.nanoTime() - startTime;
            Stats.onSynchronized(duration);
        }
    }
}
