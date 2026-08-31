package io.github.thunkware.auto.valhalla.maven.support;

import static io.github.thunkware.auto.valhalla.maven.support.StringTool.isNotBlank;

import io.github.thunkware.auto.valhalla.maven.compiler.Javac;
import org.apache.maven.plugin.MojoFailureException;

public final class JdkVersionValidator {

    /**
     * Minimum Java feature version that can compile value classes.
     */
    public static final int MIN_VALHALLA_JDK = 28;

    public static final int MIN_MAVEN_JDK = 8;

    private JdkVersionValidator() {
        throw new AssertionError();
    }

    /**
     * Accepts any JDK28+ javac the plugin would compile value classes with:
     * the consuming project's {@code <executable>} configuration, a
     * {@code java<N>.home}/{@code JAVA<N>_HOME} (N >= 28), or Maven running on
     * JDK 28 itself.
     */
    public static void validate(ConfigEvaluator configEvaluator) throws MojoFailureException {
        String executableOverride = configEvaluator.resolveString("executable");
        // The environment scan (java<N>.home / JAVA<N>_HOME) belongs here, to
        // the entry point the mojos use, so the (int, String) forms below stay
        // deterministic and don't change with the caller's environment.
        boolean hasValhallaCompiler = isNotBlank(executableOverride)
                || Javac.hasValhallaCompiler(null, MIN_VALHALLA_JDK);
        validate(jdkVersion(), hasValhallaCompiler);
    }

    /**
     * Validates against an explicitly-configured {@code java<N>.home} only;
     * the ambient environment is deliberately not consulted.
     */
    static void validate(int version, String java28Home) throws MojoFailureException {
        validate(version, isNotBlank(java28Home));
    }

    /**
     * The Java feature version of the current JVM (28 for JDK 28), parsed from
     * the specification version so it works on every JDK.
     */
    private static int jdkVersion() {
        String spec = System.getProperty("java.specification.version", "1.8");
        if (spec.startsWith("1.")) {
            spec = spec.substring(2);
        }
        try {
            return Integer.parseInt(spec);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    private static void validate(int version, boolean hasValhallaCompiler)
            throws MojoFailureException {
        if (version < MIN_MAVEN_JDK) {
            throw new MojoFailureException("auto-valhalla: Maven must run on JDK 8 or greater. got JDK " + version);
        }
        if (version < MIN_VALHALLA_JDK && !hasValhallaCompiler) {
            throw new MojoFailureException("auto-valhalla: running on JDK " + version
                    + " requires a JDK 28 javac; set java28.home / JAVA28_HOME "
                    + "or configure <executable>");
        }
    }
}
