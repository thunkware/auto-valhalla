package io.github.thunkware.auto.valhalla;

import java.lang.instrument.Instrumentation;

/**
 * JDK 5 compatible agent entry point.
 *
 * <p>This class is compiled with a real JDK 5 compiler so its class file is
 * version 49 and can be loaded on any JVM from JDK 5 upwards. It performs a
 * minimum-specification check and, when the running JVM is new enough, delegates
 * directly to {@link AutoValhallaAgent} (the real, JDK 28 implementation, which
 * is unpacked into the same jar and replaces this module's placeholder
 * {@link AutoValhallaAgent} at packaging time).
 *
 * <p>When the JVM is too old (below {@link #MIN_JDK}), the agent prints a
 * warning and returns silently, leaving application classes untouched.
 */
public final class AutoValhallaAgent5 {

    /** Minimum Java specification version required for real transformation. */
    private static final int MIN_JDK = 28;

    private AutoValhallaAgent5() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        if (!isSupported()) {
            warnUnsupported("premain");
            return;
        }
        AutoValhallaAgent.premain(args, instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        if (!isSupported()) {
            warnUnsupported("agentmain");
            return;
        }
        AutoValhallaAgent.agentmain(args, instrumentation);
    }

    private static boolean isSupported() {
        return jdkFeature() >= MIN_JDK;
    }

    private static int jdkFeature() {
        String spec = System.getProperty("java.specification.version", "1.5");
        if (spec.startsWith("1.")) {
            spec = spec.substring(2);
        }
        try {
            return Integer.parseInt(spec);
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private static void warnUnsupported(String entry) {
        System.err.println(
                "[AutoValhalla] WARNING: agent started via " + entry
                        + " on an unsupported JVM (Java " + jdkFeature()
                        + "). Project Valhalla requires JDK " + MIN_JDK
                        + "+ with --enable-preview. The agent is disabled and"
                        + " application classes are left unchanged.");
    }
}
