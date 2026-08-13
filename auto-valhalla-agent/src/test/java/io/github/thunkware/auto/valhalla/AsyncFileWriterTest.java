package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AsyncFileWriter}: producer-consumer queue-based file writing
 * with background flushing and shutdown hook cleanup.
 */
class AsyncFileWriterTest {

    @Test
    void recordsAreWrittenToFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("records.txt");
        AsyncFileWriter writer = AsyncFileWriter.forFile(file.toString());

        writer.record("com.example.Foo");
        writer.record("com.example.Bar");

        // Give the background thread time to write and flush
        Thread.sleep(1100);

        String content = Files.readString(file);
        assertTrue(content.contains("com.example.Foo"), "Foo must be written");
        assertTrue(content.contains("com.example.Bar"), "Bar must be written");
    }

    @Test
    void deduplicatesOnSecondRecord(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dedup.txt");
        AsyncFileWriter writer = AsyncFileWriter.forFile(file.toString());

        writer.record("com.example.Foo");
        writer.record("com.example.Foo"); // duplicate

        Thread.sleep(1100);

        String content = Files.readString(file);
        long count = content.lines().filter(line -> line.equals("com.example.Foo")).count();
        assertEquals(1, count, "Foo must appear exactly once, got " + count);
    }

    @Test
    void preexistingNamesAreNotReappended(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("existing.txt");
        Files.writeString(file, "com.example.Existing\n");

        AsyncFileWriter writer = AsyncFileWriter.forFile(file.toString());
        writer.record("com.example.Existing"); // already in file
        writer.record("com.example.New");

        Thread.sleep(1100);

        String content = Files.readString(file);
        long existingCount = content.lines()
                .filter(line -> line.equals("com.example.Existing"))
                .count();
        assertEquals(1, existingCount, "Existing must appear once (not reappended)");
        assertTrue(content.contains("com.example.New"), "New must be added");
    }

    @Test
    void multipleFilesShareNoState(@TempDir Path dir) throws Exception {
        Path file1 = dir.resolve("file1.txt");
        Path file2 = dir.resolve("file2.txt");

        AsyncFileWriter writer1 = AsyncFileWriter.forFile(file1.toString());
        AsyncFileWriter writer2 = AsyncFileWriter.forFile(file2.toString());

        writer1.record("com.example.File1");
        writer2.record("com.example.File2");

        Thread.sleep(1100);

        String content1 = Files.readString(file1);
        String content2 = Files.readString(file2);

        assertTrue(content1.contains("com.example.File1"), "File1 must contain File1 record");
        assertFalse(content1.contains("com.example.File2"), "File1 must not contain File2 record");
        assertTrue(content2.contains("com.example.File2"), "File2 must contain File2 record");
        assertFalse(content2.contains("com.example.File1"), "File2 must not contain File1 record");
    }

    @Test
    void samePathReturnsCachedInstance(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cached.txt");

        AsyncFileWriter writer1 = AsyncFileWriter.forFile(file.toString());
        AsyncFileWriter writer2 = AsyncFileWriter.forFile(file.toString());

        // Same instance (both go to same queue, both see same SEEN set)
        assertSame(writer1, writer2, "Same path must return cached instance");

        writer1.record("com.example.Foo");
        writer2.record("com.example.Foo"); // duplicate via different handle

        Thread.sleep(1100);

        String content = Files.readString(file);
        long count = content.lines().filter(line -> line.equals("com.example.Foo")).count();
        assertEquals(1, count, "Duplicate across cached instances must deduplicate");
    }

    @Test
    void nullOrEmptyPathReturnsNull() {
        assertNull(AsyncFileWriter.forFile(null), "null path returns null");
        assertNull(AsyncFileWriter.forFile(""), "empty path returns null");
    }

    @Test
    void nullOrEmptyNamesAreIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nulls.txt");
        AsyncFileWriter writer = AsyncFileWriter.forFile(file.toString());

        writer.record(null);
        writer.record("");
        writer.record("com.example.Valid");

        Thread.sleep(1100);

        String content = Files.readString(file);
        assertTrue(content.contains("com.example.Valid"), "Valid record must be written");
    }

    @Test
    void flushHappensWithin1Second(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("flush_timing.txt");
        AsyncFileWriter writer = AsyncFileWriter.forFile(file.toString());

        writer.record("com.example.Test");

        // Wait < 1s: data may not be flushed yet (in queue)
        Thread.sleep(200);

        // Data must appear within 1s (flush interval is 1000ms)
        for (int i = 0; i < 10; i++) {
            if (Files.exists(file) && Files.readString(file).contains("com.example.Test")) {
                break;
            }
            Thread.sleep(100);
        }

        assertTrue(Files.readString(file).contains("com.example.Test"),
                "Record must be flushed within 1 second");
    }
}
