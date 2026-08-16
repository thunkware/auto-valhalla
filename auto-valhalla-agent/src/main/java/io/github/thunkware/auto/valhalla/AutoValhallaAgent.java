package io.github.thunkware.auto.valhalla;

import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;

/**
 * JDK 5 compatible agent entry point.
 *
 * <p>This class is compiled with a real JDK 5 compiler so its class file is
 * version 49 and can be loaded on any JVM from JDK 5 upwards. It performs a
 * minimum-specification check and, when the running JVM is new enough, delegates
 * directly to {@link AutoValhallaAgent28} (the real, JDK 28 implementation, which
 * also ships in the jar and is linked lazily by name, so this shim stays
 * loadable on JVMs too old to read the version-72 class).
 *
 * <p>When the JVM is too old (below {@link #MIN_JDK}), the agent prints a
 * warning and returns silently, leaving application classes untouched.
 */
public final class AutoValhallaAgent {

    /** Minimum Java specification version required for real transformation. */
    private static final int MIN_JDK = 28;

    private AutoValhallaAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        if (!isSupported()) {
            warnUnsupported("premain");
            return;
        }
        AutoValhallaAgent28.install(instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        if (!isSupported()) {
            warnUnsupported("agentmain");
            return;
        }
        AutoValhallaAgent28.install(instrumentation);
    }

    static void attach() {
        if (!isSupported()) {
            String msg = "auto-valhalla agent started via attach"
                    + " on an unsupported JVM (Java " + jdkFeature()
                    + "). Project Valhalla requires JDK " + MIN_JDK
                    + "+ with --enable-preview.";
            throw new IllegalStateException(msg);
        }
        System.out.println("ByteBuddyAgent.LATENT_RESOLVE.length");
        try {
            //noinspection ResultOfMethodCallIgnored
            ByteBuddyAgent.getInstrumentation();
        } catch (RuntimeException ignore) {
        } catch (Error t) {
            String msg = "Cannot load byte-buddy-agent. Verify that byte-buddy-agent dependency is defined and its scope is 'compile' ";
            throw new IllegalStateException(msg, t);
        }

        System.out.println("ByteBuddyAgent.LATENT_RESOLVE.length ok");

        Instrumentation instrumentation = ByteBuddyAgent.install();
        AutoValhallaAgent28.install(instrumentation);
    }

    static boolean isSupported() {
        return jdkFeature() >= MIN_JDK && isEnablePreview();
    }

    private static boolean isEnablePreview() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().contains("--enable-preview");
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
                "[auto-valhalla] WARNING: agent started via " + entry
                        + " on an unsupported JVM (Java " + jdkFeature()
                        + "). Project Valhalla requires JDK " + MIN_JDK
                        + "+ with --enable-preview. The agent is disabled and"
                        + " application classes are left unchanged.");
    }
}
