package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import io.github.thunkware.auto.valhalla.logger.InternalLoggerFactory;

import io.github.thunkware.auto.valhalla.util.Failable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Monitors synchronization attempts in instrumented classes. Called before each
 * {@code monitorenter} instruction when {@code Mode.SYNCHRONIZATION_MONITOR} is
 * enabled.
 *
 * <p>Logs each first-seen class name (per JVM run) at the configured level from
 * a background thread (names are queued, so the monitor never blocks the
 * synchronized block on logging). Optionally also records class names to a file
 * via {@link BackgroundFileWriter} when {@code synchronization-monitor.append-to}
 * is configured (file writes are deduplicated against existing file contents;
 * the log seen-set is always empty at start-up regardless).
 *
 * <p>This class must be loadable from the instrumented application classes (it
 * lives in the agent's own package, which is never transformed) and must never
 * throw, so every operation is defensive.
 *
 * <h2>Class-loader visibility</h2>
 * {@link SynchronizationInstrumenter} injects a direct
 * {@code invokestatic SynchronizationMonitor.onSynchronized} into application
 * classes, so their class loader must be able to resolve this class. That holds
 * for the usual case (the agent jar is appended to the system class path), but
 * not for a loader that does not delegate to the system class path — OSGi
 * bundles, some application-server and plugin loaders — where entering a
 * synchronized block would fail with {@code NoClassDefFoundError}.
 *
 * <p>For those setups, start the JVM with the undocumented flag
 * <pre>{@code
 * -Dio.github.thunkware.auto.valhalla.SynchronizationMonitor.appendToBootstrapClassLoaderSearch=true
 * }</pre>
 * which makes {@code AutoValhallaAgent} add the agent jar to the bootstrap class
 * loader search before any other agent class is loaded, so every loader can see
 * this class. It is off by default because it loads the whole agent from the
 * bootstrap loader, which cannot be undone for the lifetime of the JVM, and
 * because appending to the bootstrap class path makes the JVM fall back to
 * boot-loader-only class-data sharing ("Sharing is only supported for boot loader
 * classes because bootstrap classpath has been appended"), which costs start-up
 * time. See {@code AutoValhallaAgent.appendAgentJarToBootstrap}.
 */
public final class SynchronizationMonitor {

    public static final String ON_SYNCHRONIZED = "onSynchronized";
    private static final long LOG_INTERVAL_MS = 1000;

    private static volatile BackgroundFileWriter writer;
    private static final Set<String> logSeen = ConcurrentHashMap.newKeySet();
    private static final LinkedBlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

    static {
        Thread.ofVirtual()
              .name("auto-valhalla-SynchronizationMonitor-Logger")
              .start(SynchronizationMonitor::logLoop);
    }

    private SynchronizationMonitor() {}

    /** Optionally enables file recording to {@code path} (may be {@code null} or
     *  empty). Monitoring is always active once instrumented code is present;
     *  log verbosity is controlled via
     *  {@code logging.level.auto-valhalla.synchronization-monitor}. */
    public static void configure(String path) {
        if (path != null && !path.isEmpty()) {
            writer = BackgroundFileWriter.forFile(path);
        }
    }

    /** Called immediately before each {@code monitorenter} with the object being
     *  locked. Queues the first occurrence of each class per JVM run at the
     *  configured level; also appends to the file (deduplicated) if one is
     *  configured. */
    // keep this method really fast, and clear of non-JDK stuff
    public static void onSynchronized(Object o) {
        if (o == null) {
            return;
        }
        long startTime = System.nanoTime();
        try {
            String name = o.getClass().getName();
            BackgroundFileWriter localWriter = writer;
            if (localWriter != null) {
                // append in background
                localWriter.record(name);
            }
            if (logSeen.add(name)) {
                // log in background
                logQueue.add(name);
            }
        } catch (Throwable t) {
            // the monitor must never affect the synchronized block
            // don't even (debug) log the exception
        } finally {
            long duration = System.nanoTime() - startTime;
            Stats.onSynchronized(duration);
        }
    }

    private static void logLoop() {
        InternalLogger syncLog = InternalLoggerFactory.getLogger("auto-valhalla.synchronization-monitor");
        while (!Thread.currentThread().isInterrupted()) {
            String name = Failable.callQuietly(() -> logQueue.poll(LOG_INTERVAL_MS, TimeUnit.MILLISECONDS));
            if (name != null) {
                Failable.runQuietly(() -> syncLog.info("Synchronized on: " + name));
            }
        }
    }
}
