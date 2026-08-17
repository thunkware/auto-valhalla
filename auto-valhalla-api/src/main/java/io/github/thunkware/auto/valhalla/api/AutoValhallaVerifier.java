package io.github.thunkware.auto.valhalla.api;

import java.util.List;

/**
 * Checks at build time that classes marked {@link AutoValhalla} satisfy
 * the structural prerequisites the agent enforces at load/deploy time.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link #safe()} — returns a {@link ConfiguredVerifier} for {@code safe} mode
 *       (class must already be {@code final}). Chain builder methods before calling
 *       {@link ConfiguredVerifier#verify verify} or {@link ConfiguredVerifier#violations violations}.
 *   <li>{@link #verify(Class[])} — shorthand for {@code safe().verify(classes)}.
 *   <li>{@link #violations(Class[])} — shorthand for {@code safe().violations(classes)}.
 * </ul>
 *
 * <pre>
 * // safe mode
 * AutoValhallaVerifier.safe().verify(Foo.class, Bar.class);
 * // equivalent shorthand
 * AutoValhallaVerifier.verify(Foo.class, Bar.class);
 *
 * // with extra modes
 * AutoValhallaVerifier.safe()
 *     .removeSynchronized()
 *     .markClassFinal()
 *     .markFieldsFinal()
 *     .verify(Foo.class, Bar.class);
 * </pre>
 */
public final class AutoValhallaVerifier {

    private AutoValhallaVerifier() {}

    /** Returns a {@link ConfiguredVerifier} for {@code safe} mode (class must already be {@code final}). */
    public static ConfiguredVerifier safe() {
        return new ConfiguredVerifier(false, false, false);
    }

    /** Shorthand for {@code safe().markClassFinal()}. */
    public static ConfiguredVerifier markClassFinal() {
        return safe().markClassFinal();
    }

    /** Shorthand for {@code safe().removeSynchronized()}. */
    public static ConfiguredVerifier removeSynchronized() {
        return safe().removeSynchronized();
    }

    /** Shorthand for {@code safe().markFieldsFinal()}. */
    public static ConfiguredVerifier markFieldsFinal() {
        return safe().markFieldsFinal();
    }

    /**
     * Shorthand for {@code safe().violations(classes)}.
     *
     * @see ConfiguredVerifier#violations(Class...)
     */
    public static List<String> violations(Class<?>... classes) {
        return safe().violations(classes);
    }

    /**
     * Shorthand for {@code safe().verify(classes)}.
     *
     * @throws IllegalArgumentException if one or more classes fail the structural checks
     * @see ConfiguredVerifier#verify(Class...)
     */
    public static void verify(Class<?>... classes) {
        safe().verify(classes);
    }
}
