package io.github.thunkware.auto.valhalla;

import java.lang.instrument.Instrumentation;

/**
 * Compiled-for-JDK-5 placeholder so {@link AutoValhallaAgent5} can reference the
 * agent entry points directly (same package, so no reflection is needed).
 *
 * <p>This class is <strong>never used at runtime</strong> in the published agent
 * jar: the {@code agent} module excludes it when unpacking the {@code agent5}
 * artifact and supplies the real JDK 28 implementation instead. It exists only
 * so the shim has a JDK 5-loadable symbol to compile and link against.
 */
public final class AutoValhallaAgent {

    private AutoValhallaAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        // Replaced by the real (JDK 28) AutoValhallaAgent at packaging time.
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        // Replaced by the real (JDK 28) AutoValhallaAgent at packaging time.
    }
}
