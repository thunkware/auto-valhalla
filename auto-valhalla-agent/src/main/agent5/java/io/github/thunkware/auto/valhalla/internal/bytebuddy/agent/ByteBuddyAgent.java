package io.github.thunkware.auto.valhalla.internal.bytebuddy.agent;

import java.lang.instrument.Instrumentation;

/**
 * Compile-time-only placeholder so {@code AutoValhallaAttachAgent} can reference
 * the (shaded) Byte Buddy attach API without a real Byte Buddy on the JDK 5
 * compile classpath. Replaced at runtime by the relocated Byte Buddy classes.
 */
public final class ByteBuddyAgent {

    private ByteBuddyAgent() {
    }

    public static Instrumentation install() {
        throw new UnsupportedOperationException("compile-time placeholder");
    }
}
