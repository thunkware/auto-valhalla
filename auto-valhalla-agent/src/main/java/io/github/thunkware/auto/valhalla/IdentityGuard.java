package io.github.thunkware.auto.valhalla;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A static guard injected in front of every {@code monitorenter} instruction
 * when {@code auto-valhalla.identity-exception-append-to} is configured.
 *
 * <p>Value objects have no identity, so the JVM throws
 * {@link java.lang.IdentityException} when code synchronizes on one. The class
 * that does the synchronizing is typically a plain identity class (it is left
 * identity, so the guard still runs there); the guarded {@code monitorenter}
 * then records the <em>value class</em> being locked, so a later run can add it
 * to {@code excludes-file} and stop converting it.
 *
 * <p>This class must be loadable from the instrumented application classes (it
 * lives in the agent's own package, which is never transformed) and must never
 * throw, so every operation is defensive.
 */
public final class IdentityGuard {

    private static volatile String appendTo;
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private IdentityGuard() {}

    /** Enables recording to {@code path}. The file is read once so names already
     *  present are not appended again; a missing file is treated as empty. */
    public static void configure(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        appendTo = path;
        Path p = Path.of(path);
        if (Files.exists(p)) {
            try {
                for (String line : Files.readAllLines(p)) {
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        SEEN.add(t);
                    }
                }
            } catch (IOException ignored) {
                // never fail configuration on an unreadable file
            }
        }
    }

    /** Called immediately before each {@code monitorenter} with the object being
     *  locked. If it is a value class, records its name for future exclusion. */
    public static void check(Object o) {
        if (o == null || appendTo == null) {
            return;
        }
        try {
            if (o.getClass().isValue()) {
                record(o.getClass().getName());
            }
        } catch (Throwable ignored) {
            // the guard must never affect the synchronized block
        }
    }

    private static void record(String name) {
        if (!SEEN.add(name)) {
            return;
        }
        try {
            Files.writeString(Path.of(appendTo), name + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // best-effort: dropping a duplicate record is harmless
        }
    }
}
