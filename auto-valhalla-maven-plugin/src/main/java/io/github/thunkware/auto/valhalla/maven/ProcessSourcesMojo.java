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
 * Runs only the {@code auto-valhalla} annotation processor: a
 * {@code javac -proc:only} selection pass over the project's source roots that
 * selects the {@code @AutoValhalla}-annotated top-level types and stages
 * adapted copies of their source files (with {@code value class}/
 * {@code value record}) under
 * {@code <buildDirectory>/auto-valhalla-jdk28/selected}, together with the
 * {@code selection.txt} manifest.
 *
 * <p>Unlike the {@code transform} goal, nothing is compiled and nothing is
 * written under the project's output directory, so this goal is useful to
 * inspect (or post-process) what would be transformed without producing the
 * multi-release value classes.
 *
 * <p>Like {@link TransformMojo}, running Maven on a JDK older than 28 does
 * not fail the build: the goal logs a warning and skips the pass. An annotated
 * class that the processor cannot adapt fails the build when
 * {@code failOnAnnotationFailure} is set (the default).
 */
@Mojo(name = "process-sources", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class ProcessSourcesMojo extends AbstractMojo {

    /**
     * Skip the annotation-processor pass entirely.
     */
    @Parameter(defaultValue = "false", property = "auto-valhalla.skip")
    private boolean skip;

    /**
     * Maven's {@code target} directory, used for the staging area that
     * receives the adapted sources and the selection manifest.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    /**
     * Fail the build when an annotated class cannot be adapted by the
     * processor. Mirrors the agent's {@code annotation.rejected}/
     * {@code annotation.fail} defaulting to {@code fatal}.
     */
    @Parameter(defaultValue = "true")
    private boolean failOnAnnotationFailure;

    /**
     * Override for the JDK compiler executable; defaults to
     * {@code <java.home>/bin/javac}.
     */
    @Parameter(property = "auto-valhalla.javac")
    private String javac;

    /**
     * Character encoding for the selection pass. Defaults to
     * {@code ${project.build.sourceEncoding}} or UTF-8.
     */
    @Parameter(property = "auto-valhalla.encoding", defaultValue = "${project.build.sourceEncoding}")
    private String encoding;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("auto-valhalla: skipping source processing");
            return;
        }
        int feature = TransformMojo.jdkFeature();
        if (feature < TransformMojo.MIN_JDK) {
            getLog().warn("auto-valhalla: running on Java " + feature
                    + "; Project Valhalla requires JDK " + TransformMojo.MIN_JDK
                    + "+. Skipping source processing.");
            return;
        }
        List<String> compileClasspath;
        try {
            compileClasspath = project.getCompileClasspathElements();
        } catch (org.apache.maven.artifact.DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("auto-valhalla: could not resolve the project's "
                    + "compile classpath for javac: " + e.getMessage(), e);
        }
        String processorPath = AutoValhallaProcessor.processorPath();
        if (processorPath == null) {
            throw new MojoExecutionException("auto-valhalla: could not locate the "
                    + "auto-valhalla-processor jar for javac's -processorpath");
        }
        AnnotationProcessorRunner.Selection result;
        try {
            Input input = Input.builder()
                    .sourceRoots(project.getCompileSourceRoots())
                    .buildDirectory(buildDirectory)
                    .javac(javacExecutable())
                    .processorPath(processorPath)
                    .compileClasspath(compileClasspath)
                    .encoding(resolveEncoding())
                    .build();
            result = AnnotationProcessorRunner.run(input);
        } catch (IOException e) {
            throw new MojoExecutionException("auto-valhalla: failed during the "
                    + "annotation-processor pass: " + e.getMessage(), e);
        }
        for (String failure : result.failures()) {
            getLog().error("auto-valhalla: " + failure);
        }
        if (!result.failures().isEmpty() && failOnAnnotationFailure) {
            throw new MojoFailureException("auto-valhalla: " + result.failures().size()
                    + " annotation-selected class(es) could not be adapted:\n  - "
                    + String.join("\n  - ", result.failures()));
        }
        if (result.selectedTypes().isEmpty()) {
            getLog().info("auto-valhalla: no @AutoValhalla-annotated classes found");
        } else {
            getLog().info("auto-valhalla: processed " + result.selectedTypes().size()
                    + " @AutoValhalla-annotated class(es); adapted sources staged under "
                    + result.stagedSources().getAbsolutePath());
        }
    }

    private String resolveEncoding() {
        String enc = encoding;
        if (enc == null || enc.trim().isEmpty()) {
            return "UTF-8";
        }
        return enc.trim();
    }

    private String javacExecutable() {
        if (javac != null && !javac.trim().isEmpty()) {
            return javac.trim();
        }
        return new File(System.getProperty("java.home", "java"),
                "bin/javac").getAbsolutePath();
    }
}
