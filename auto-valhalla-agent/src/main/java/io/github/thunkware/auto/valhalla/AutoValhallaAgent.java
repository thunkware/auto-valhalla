package io.github.thunkware.auto.valhalla;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.jar.JarFile;

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

    /** Undocumented flag; see {@link #appendAgentJarToBootstrap} and the note in
     *  {@code SynchronizationMonitor}. Spelled out here because this class is
     *  compiled against the JDK 5 API only and cannot reference that class. */
    static final String APPEND_TO_BOOTSTRAP =
            "io.github.thunkware.auto.valhalla.SynchronizationMonitor.appendToBootstrapClassLoaderSearch";

    private AutoValhallaAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        if (!isSupported()) {
            warnUnsupported("premain");
            return;
        }
        appendAgentJarToBootstrap(instrumentation);
        AutoValhallaAgent28.install(instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        if (!isSupported()) {
            warnUnsupported("agentmain");
            return;
        }
        appendAgentJarToBootstrap(instrumentation);
        AutoValhallaAgent28.install(instrumentation);
    }

    /**
     * Appends the agent jar to the bootstrap class loader search when
     * {@code -Dio.github.thunkware.auto.valhalla.SynchronizationMonitor.appendToBootstrapClassLoaderSearch=true}
     * is set. Instrumented classes call
     * {@code SynchronizationMonitor.onSynchronized} directly, which fails with a
     * {@code NoClassDefFoundError} in a class loader that cannot see the system
     * class path (OSGi, some application servers, plugin loaders); making the
     * agent visible to every loader fixes that.
     *
     * <p>Deliberately done here, in the shim, and before any other agent class is
     * touched: once the jar is on the bootstrap search, the rest of the agent is
     * loaded from there, so there is exactly one copy of its classes and one copy
     * of its configuration. Doing it later would leave two.
     *
     * <p>Off by default, and a best-effort operation: any failure is reported to
     * stderr and the agent continues, since the flag only affects reachability of
     * the monitor hook.
     */
    static void appendAgentJarToBootstrap(Instrumentation instrumentation) {
        if (!Boolean.getBoolean(APPEND_TO_BOOTSTRAP)) {
            return;
        }
        try {
            File jar = agentJar();
            if (jar == null || !jar.isFile()) {
                System.err.println("[auto-valhalla] WARNING: " + APPEND_TO_BOOTSTRAP
                        + " is set but the agent is not running from a jar file; ignored.");
                return;
            }
            JarFile jarFile = new JarFile(jar);
            try {
                // Compiled against the JDK 5 API, which has no
                // appendToBootstrapClassLoaderSearch (added in Java 6), so call it
                // reflectively on the interface (its implementation class is not
                // exported and cannot be reflected on).
                Method append = Instrumentation.class.getMethod(
                        "appendToBootstrapClassLoaderSearch", new Class[]{JarFile.class});
                append.invoke(instrumentation, new Object[]{jarFile});
            } finally {
                jarFile.close();
            }
        } catch (Throwable t) {
            System.err.println("[auto-valhalla] WARNING: cannot append " + APPEND_TO_BOOTSTRAP
                    + ": " + t);
        }
    }

    private static File agentJar() throws Exception {
        CodeSource source = AutoValhallaAgent.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        return new File(source.getLocation().toURI());
    }

    public static boolean isSupported() {
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

    static void warnUnsupported(String entry) {
        System.err.println(
                "[auto-valhalla] WARNING: agent started via " + entry
                        + " on an unsupported JVM (Java " + jdkFeature()
                        + "). Project Valhalla requires JDK " + MIN_JDK
                        + "+ with --enable-preview. The agent is disabled and"
                        + " application classes are left unchanged.");
    }
}
