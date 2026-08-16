package io.github.thunkware.auto.valhalla;

/**
 * Programmatic entry point for attaching the auto-valhalla agent to an
 * already-running JVM, instead of launching the application with
 * {@code -javaagent}.
 *
 * <p>This class is compiled with a real JDK 5 compiler (class-file version 49)
 * so it can be loaded on any JVM from JDK 5 upwards. It delegates to
 * {@link AutoValhallaAgent}, which performs the actual attach via
 * and lazily links to the real
 * {@link AutoValhallaAgent28} implementation only when the JVM supports it.
 *
 * <p>Add the {@code auto-valhalla-agent-attach} dependency and call
 * {@link #attach()} as early as possible at startup, e.g. from a static initializer.
 */
public final class AutoValhallaAttachAgent {

    private AutoValhallaAttachAgent() {
    }

    /**
     * Attaches the auto-valhalla agent to the current JVM and installs the
     * value-class transformer.
     *
     * <p>Call this as early as possible at application startup so that as many
     * application classes as possible are loaded after the transformer is
     * installed; classes already loaded before attach are not rewritten.
     *
     * @throws IllegalStateException if the running JVM does not support value
     *         classes (JDK 28+ launched with {@code --enable-preview}); see
     *         {@link #isSupported()}.
     */
    public static void attach() {
        AutoValhallaAgent.attach();
    }

    /**
     * Returns whether the running JVM supports value-class transformation,
     * i.e. JDK 28 or newer launched with {@code --enable-preview}.
     *
     * @return {@code true} if {@link #attach()} can succeed, {@code false} if
     *         the JVM is too old or preview features are disabled.
     */
    public static boolean isSupported() {
        return AutoValhallaAgent.isSupported();
    }
}
