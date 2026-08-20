package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;

/**
 * Turns {@code @AutoValhalla}-annotated classes (and classes whose package
 * matches the {@code includes} patterns) into JEP 401 value classes at compile
 * time and writes them under {@code META-INF/versions/<versionDirectory>} so the
 * jar becomes a multi-release jar whose value-class variants are used on JDK 28+
 * and whose original identity classes are used everywhere else.
 *
 * <p>There is no bytecode rewriting: for every selected source file the goal
 * copies it into a staging directory with its {@code class}/{@code record}
 * declarations adapted into {@code value class}/{@code value record}, and then
 * delegates to the JDK compiler ({@code javac --release <N> --enable-preview}),
 * which produces the value-class files natively. The base classes are left
 * untouched, so they keep working on JDKs older than 28.
 *
 * <p>The goal performs no compilation of its own: bind it to the lifecycle after
 * {@code compile} (the default {@code process-classes} phase) so the project's
 * other classes exist when the value classes are compiled; those other classes
 * are linked from {@code target/classes}, which is on the javac classpath.
 *
 * <p>Running Maven on a JDK older than 28 does not fail the build: the goal
 * logs a warning and leaves the classes as identity classes, mirroring the
 * javaagent's behavior on an unsupported JVM.
 *
 * <p>Selection and failure handling mirror the agent:
 * <ul>
 *   <li>{@code @AutoValhalla} annotation is the in-source opt-in;</li>
 *   <li>{@code includes}/{@code excludes} accept dotted class/package
 *       patterns ({@code *} matches everything);</li>
 *   <li>a class selected by both counts as annotation-selected only;</li>
 *   <li>by default an annotation-selected class that javac rejects (because it
 *       cannot be a value class) fails the build, while an includes-selected one
 *       is logged and skipped ({@code failOnAnnotationFailure}/
 *       {@code failOnIncludesFailure}).</li>
 * </ul>
 */
@Mojo(name = "transform", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true,
        requiresProject = true, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class AutoValhallaMojo extends AbstractMojo {

    /** Minimum Java feature version that can compile value classes. */
    static final int MIN_JDK = 28;

    /** Skip the transformation entirely. */
    @Parameter(defaultValue = "false", property = "auto-valhalla.skip")
    private boolean skip;

    /** The compiled classes directory; the versioned value classes are written
     *  under {@code META-INF/versions/<versionDirectory>} here. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File outputDirectory;

    /** Maven's {@code target} directory, used for the staging area. */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    /** Dotted class/package patterns selected for conversion ({@code *} matches
     *  everything; a package pattern matches the package and its subpackages;
     *  slashes are not accepted). */
    @Parameter(property = "auto-valhalla.includes")
    private List<String> includes;

    /** Dotted class/package patterns never converted, checked first. */
    @Parameter(property = "auto-valhalla.excludes")
    private List<String> excludes;

    /** The multi-release version directory ({@code META-INF/versions/<N>}) that
     *  receives the value-class variants. Must be at least 28, and not higher
     *  than the JDK running Maven: it becomes the compiler's {@code --release}. */
    @Parameter(defaultValue = "28", property = "auto-valhalla.version")
    private int versionDirectory;

    /** Fail the build when an annotation-selected class cannot be compiled as a
     *  value class. Mirrors the agent's {@code annotation.rejected}/
     *  {@code annotation.fail} defaulting to {@code fatal}. */
    @Parameter(defaultValue = "true")
    private boolean failOnAnnotationFailure;

    /** Fail the build when an includes-selected class cannot be compiled as a
     *  value class. Mirrors the agent's {@code includes.rejected}/
     *  {@code includes.fail} defaulting to {@code debug} (skip). */
    @Parameter(defaultValue = "false")
    private boolean failOnIncludesFailure;

    /** Override for the JDK compiler executable; defaults to
     *  {@code <java.home>/bin/javac}. */
    @Parameter(property = "auto-valhalla.javac")
    private String javac;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("auto-valhalla: skipping transformation");
            return;
        }
        int feature = jdkFeature();
        if (feature < MIN_JDK) {
            getLog().warn("auto-valhalla: running on Java " + feature
                    + "; Project Valhalla requires JDK " + MIN_JDK
                    + "+. Skipping transformation, classes remain identity classes.");
            return;
        }
        if (versionDirectory < MIN_JDK) {
            throw new MojoFailureException("auto-valhalla: versionDirectory must be at least "
                    + MIN_JDK + " (a multi-release version directory below the class-file "
                    + "feature version is rejected by the JVM); got " + versionDirectory);
        }
        if (versionDirectory > feature) {
            throw new MojoFailureException("auto-valhalla: versionDirectory " + versionDirectory
                    + " is higher than the JDK running Maven (" + feature
                    + "); javac cannot emit class files newer than the compiler. Set "
                    + "auto-valhalla.version to at most " + feature + ".");
        }
        List<String> compileClasspath;
        try {
            compileClasspath = project.getCompileClasspathElements();
        } catch (org.apache.maven.artifact.DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("auto-valhalla: could not resolve the project's "
                    + "compile classpath for javac: " + e.getMessage(), e);
        }
        AutoValhallaSourceTransformer.Result result;
        try {
            String processorPath = AutoValhallaProcessor.processorPath();
            if (processorPath == null) {
                throw new MojoExecutionException("auto-valhalla: could not locate the "
                        + "auto-valhalla-processor jar for javac's -processorpath");
            }
            result = AutoValhallaSourceTransformer.transform(
                    project.getCompileSourceRoots(), includes, excludes, versionDirectory,
                    outputDirectory, buildDirectory, javacExecutable(), processorPath,
                    compileClasspath);
        } catch (IOException e) {
            throw new MojoExecutionException("auto-valhalla: failed during the source-level "
                    + "transformation: " + e.getMessage(), e);
        }
        for (String failure : result.annotationFailures()) {
            getLog().error("auto-valhalla: " + failure
                    + "; leaving as an identity class");
        }
        for (String failure : result.includesFailures()) {
            getLog().warn("auto-valhalla: " + failure
                    + "; leaving as an identity class");
        }
        if (!result.annotationFailures().isEmpty() && failOnAnnotationFailure) {
            throw new MojoFailureException("auto-valhalla: " + result.annotationFailures().size()
                    + " annotation-selected class(es) could not be compiled as value classes:\n  - "
                    + String.join("\n  - ", result.annotationFailures()));
        }
        if (!result.includesFailures().isEmpty() && failOnIncludesFailure) {
            throw new MojoFailureException("auto-valhalla: " + result.includesFailures().size()
                    + " includes-selected class(es) could not be compiled as value classes:\n  - "
                    + String.join("\n  - ", result.includesFailures()));
        }
        if (result.convertedCount() > 0) {
            getLog().info("auto-valhalla: compiled " + result.convertedCount()
                    + " class(es) into value classes under META-INF/versions/" + versionDirectory);
        } else {
            getLog().info("auto-valhalla: no classes converted into value classes");
        }
    }

    private String javacExecutable() {
        if (javac != null && !javac.trim().isEmpty()) {
            return javac.trim();
        }
        return new File(System.getProperty("java.home", "java"),
                "bin/javac").getAbsolutePath();
    }

    /** The Java feature version of the current JVM (28 for JDK 28), parsed from
     *  the specification version so it works on every JDK. */
    static int jdkFeature() {
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
}