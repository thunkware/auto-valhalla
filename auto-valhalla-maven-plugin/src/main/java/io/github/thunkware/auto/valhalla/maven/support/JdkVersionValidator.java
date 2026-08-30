package io.github.thunkware.auto.valhalla.maven.support;

import static io.github.thunkware.auto.valhalla.maven.CompileGeneratedSourcesMojo.MIN_MAVEN_JDK;
import static io.github.thunkware.auto.valhalla.maven.CompileGeneratedSourcesMojo.MIN_VALHALLA_JDK;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.isNotBlank;

import io.github.thunkware.auto.valhalla.maven.CompileGeneratedSourcesMojo;
import io.github.thunkware.auto.valhalla.maven.compiler.Javac;
import org.apache.maven.plugin.MojoFailureException;

public final class JdkVersionValidator {

    private JdkVersionValidator() {
        throw new AssertionError();
    }

    /**
     * Accepts any JDK 28 javac the plugin would compile value classes with:
     * the consuming project's {@code <executable>} configuration, a
     * {@code java<N>.home}/{@code JAVA<N>_HOME} (N >= 28), or Maven running on
     * JDK 28 itself.
     */
    public static void validate(String executableOverride) throws MojoFailureException {
        // The environment scan (java<N>.home / JAVA<N>_HOME) belongs here, to
        // the entry point the mojos use, so the (int, String) forms below stay
        // deterministic and don't change with the caller's environment.
        boolean hasValhallaCompiler = isNotBlank(executableOverride)
                || Javac.hasValhallaCompiler(null, MIN_VALHALLA_JDK);
        validate(CompileGeneratedSourcesMojo.jdkFeature(), hasValhallaCompiler);
    }

    /**
     * Validates against an explicitly-configured {@code java<N>.home} only;
     * the ambient environment is deliberately not consulted.
     */
    public static void validate(int feature, String java28Home)
            throws MojoFailureException {
        validate(feature, isNotBlank(java28Home));
    }

    private static void validate(int feature, boolean hasValhallaCompiler)
            throws MojoFailureException {
        if (feature < MIN_MAVEN_JDK) {
            throw new MojoFailureException("auto-valhalla: Maven must run on JDK 8 through "
                    + MIN_VALHALLA_JDK + "; got JDK " + feature);
        }
        if (feature > MIN_VALHALLA_JDK) {
            throw new MojoFailureException("auto-valhalla: running on JDK " + feature
                    + " is unsupported; run Maven on JDK " + MIN_VALHALLA_JDK
                    + " or use JDK 8 through " + (MIN_VALHALLA_JDK - 1)
                    + " with a JDK 28 javac configured");
        }
        if (feature < MIN_VALHALLA_JDK && !hasValhallaCompiler) {
            throw new MojoFailureException("auto-valhalla: running on JDK " + feature
                    + " requires a JDK 28 javac; set java28.home / JAVA28_HOME "
                    + "or configure <executable>");
        }
    }
}
