package io.github.thunkware.auto.valhalla;

import java.lang.instrument.Instrumentation;

/**
 * Compiled-for-JDK-5 placeholder so {@link AutoValhallaAgent} can reference the
 * agent entry points directly (same package, so no reflection is needed).
 *
 * <p>This class is <strong>never used at runtime</strong> in the published agent
 * jar: it is only a compile-time symbol for {@link AutoValhallaAgent}, which is
 * replaced by the real JDK 28 {@code AutoValhallaAgent28} that lives in this same
 * module and is compiled separately. It exists only so the shim has a JDK
 * 5-loadable symbol to compile and link against.
 */
public final class AutoValhallaAgent28 {

    private AutoValhallaAgent28() {
    }

    public static synchronized boolean installAttempted() {
        // Replaced by the real (JDK 28) AutoValhallaAgent28 at packaging time.
        return false;
    }

    public static synchronized void install(Instrumentation inst) {
        // Replaced by the real (JDK 28) AutoValhallaAgent28 at packaging time.
    }
}
