package io.github.thunkware.auto.valhalla.maven.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Utils {

    private Utils() {
        throw new AssertionError();
    }

    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    public static void run(ThrowingRunnable runnable, Consumer<Throwable> onFail) {
        try {
            runnable.run();
        } catch (Throwable t) {
            restoreInterrupt(t);
            onFail.accept(t);
        }
    }

    /**
     * Re-asserts the interrupt flag, which throwing an {@link InterruptedException}
     * clears. Without this, swallowing the exception leaves the agent's background
     * loops — which poll {@link Thread#isInterrupted()} — running forever after
     * they have been asked to stop.
     */
    private static void restoreInterrupt(Throwable t) {
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public static List<Path> walk(Path start) throws IOException {
        return walk(start, path -> true);
    }

    public static List<Path> walkJavaFiles(Path start) throws IOException {
        return walk(start, path -> path.toString().endsWith(".java"));
    }

    public static List<Path> walk(Path start, Predicate<Path> filter) throws IOException {
        try (Stream<Path> stream = Files.walk(start)) {
            return stream.filter(filter)
                    .collect(Collectors.toList());
        }
    }

    public static byte[] toByteArray(final InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    public static boolean isNotBlank(String s) {
        return !trim(s).isEmpty();
    }

    public static String trim(String in) {
        return in == null ? "" : in.trim();
    }

    public static boolean asBoolean(Boolean value) {
        if (value == null) {
            return false;
        }
        return value;
    }

    public static String normalizeEncoding(String encoding) {
        return isNotBlank(encoding) ? trim(encoding) : "UTF-8";
    }

    public static String plural(int n) {
        return n <= 1 ? "" : "es";
    }
}
