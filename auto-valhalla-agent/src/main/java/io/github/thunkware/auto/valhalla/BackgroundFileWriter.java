package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.util.Failable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous file writer with per-file queues and background flushing.
 *
 * <p>Records are written to a {@link LinkedBlockingQueue}, and a background
 * virtual thread reads from the queue, writes to the file, and flushes every
 * 1 second. Multiple files are supported; each file has its own queue, writer,
 * and background thread.
 *
 * <p>This is used by {@link SynchronizationMonitor} and
 * {@link ValueClassTransformer} for non-blocking append operations.
 */
final class BackgroundFileWriter {

    private static final Map<String, BackgroundFileWriter> WRITERS = new ConcurrentHashMap<>();
    private static final long FLUSH_INTERVAL_MS = 1000;

    private final Object lock = new Object();
    private final Path file;
    private final Set<String> seen;
    private final LinkedBlockingQueue<String> queue;
    private final Thread writerThread;
    private BufferedWriter writer;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(BackgroundFileWriter::shutdownAll,
                "BackgroundFileWriter-Shutdown"));
    }

    /**
     * Returns or creates the async writer for the given file path. Each unique
     * path gets a single shared instance with its own queue and background thread.
     * The file is read once so names already present are not appended again.
     */
    static BackgroundFileWriter forFile(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return WRITERS.computeIfAbsent(path, p -> Failable.callQuietly(() -> new BackgroundFileWriter(p)));
    }

    private BackgroundFileWriter(String path) throws IOException {
        this.seen = ConcurrentHashMap.newKeySet();
        this.queue = new LinkedBlockingQueue<>();

        file = Path.of(path);
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    seen.add(t);
                }
            }
        }

        // Spawn a background virtual thread to read from queue and flush periodically
        this.writerThread = Thread.ofVirtual()
                .name("auto-valhalla-BackgroundFileWriter-" + file.getFileName())
                .start(() -> Failable.runQuietly(this::run));
    }

    /**
     * Records a name to this file if it has not been seen before. Non-blocking;
     * adds to queue or silently drops if queue is full (should rarely happen).
     */
    void record(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (seen.add(name)) {
            queue.add(name);
        }
    }

    private void run() throws Exception {
        long lastFlush = System.currentTimeMillis();
        while (!Thread.currentThread().isInterrupted()) {
            String name = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
            synchronized (lock) {
                if (name != null) {
                    writeLocked(name);
                    writeLocked("\n");
                }
                long now = System.currentTimeMillis();
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    if (writer != null) {
                        writer.flush();
                    }
                    lastFlush = now;
                }
            }
        }
    }

    private void writeLocked(String str) throws IOException {
        if (writer == null) {
            // Open writer in append mode, not auto-flush (background thread will flush)
            writer = new BufferedWriter(Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE));
        }
        writer.write(str);
    }

    /** Synchronously processes all pending records and flushes. For testing only. */
    static void drain() {
        for (BackgroundFileWriter w : WRITERS.values()) {
            synchronized (w.lock) {
                String pending;
                while ((pending = w.queue.poll()) != null) {
                    String finalPending = pending;
                    Failable.runQuietly(() -> {
                        w.writeLocked(finalPending);
                        w.writeLocked("\n");
                    });
                }
                if (w.writer != null) {
                    Failable.runQuietly(w.writer::flush);
                }
            }
        }
    }

    private void shutdown() throws IOException {
        writerThread.interrupt();
        synchronized (lock) {
            // Drain any records still queued before flushing: the background thread
            // may have been interrupted before it processed them.
            String pending;
            while ((pending = queue.poll()) != null) {
                writeLocked(pending);
                writeLocked("\n");
            }
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        }
    }

    /**
     * Shutdown hook: flush all writers and close resources. Called by
     * Runtime.addShutdownHook() when the JVM is shutting down.
     */
    private static void shutdownAll() {
        WRITERS.values().forEach(writer -> Failable.runQuietly(writer::shutdown));
    }
}
