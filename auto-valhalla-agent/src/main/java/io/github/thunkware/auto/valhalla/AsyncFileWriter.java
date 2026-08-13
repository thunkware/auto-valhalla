package io.github.thunkware.auto.valhalla;

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
final class AsyncFileWriter {

    private static final Map<String, AsyncFileWriter> WRITERS = new ConcurrentHashMap<>();
    private static final long FLUSH_INTERVAL_MS = 1000;

    private final Object lock = new Object();
    private final Path file;
    private BufferedWriter writer;
    private final Set<String> seen;
    private final LinkedBlockingQueue<String> queue;
    private final Thread writerThread;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(AsyncFileWriter::shutdown,
                "AsyncFileWriter-Shutdown"));
    }

    /**
     * Returns or creates the async writer for the given file path. Each unique
     * path gets a single shared instance with its own queue and background thread.
     * The file is read once so names already present are not appended again.
     */
    static AsyncFileWriter forFile(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return WRITERS.computeIfAbsent(path, p -> {
            try {
                return new AsyncFileWriter(p);
            } catch (IOException ignored) {
                // fail silently; record() will be a no-op
                return null;
            }
        });
    }

    private AsyncFileWriter(String path) throws IOException {
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
                .name("AsyncFileWriter-" + file.getFileName())
                .start(this::run);
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

    private void run() {
        try {
            long lastFlush = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted()) {
                String name = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                synchronized (lock) {
                    if (writer == null) {
                        // Open writer in append mode, not auto-flush (background thread will flush)
                        this.writer = new BufferedWriter(Files.newBufferedWriter(file,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                                StandardOpenOption.WRITE));
                    }

                    if (name != null) {
                        writer.write(name);
                        writer.write('\n');
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                        writer.flush();
                        lastFlush = now;
                    }
                }
            }
        } catch (Throwable ignored) {
            // best-effort writing; thread exits on any exception
        }
    }

    /**
     * Shutdown hook: flush all writers and close resources. Called by
     * Runtime.addShutdownHook() when the JVM is shutting down.
     */
    private static void shutdown() {
        for (AsyncFileWriter writer : WRITERS.values()) {
            try {
                // Interrupt the background thread
                writer.writerThread.interrupt();

                // Flush any remaining data
                synchronized (writer.lock) {
                    writer.writer.flush();
                    writer.writer.close();
                }
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }
}
