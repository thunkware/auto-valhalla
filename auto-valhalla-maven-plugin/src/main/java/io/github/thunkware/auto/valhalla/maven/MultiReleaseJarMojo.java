package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.io.IOException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Turns the jar built by {@code maven-jar-plugin} (in the {@code package}
 * phase) into a real multi-release jar by adding {@code Multi-Release: true}
 * to its manifest.
 *
 * <p>The {@code maven-jar-plugin} copies {@code META-INF/versions} content
 * into the jar, but the JVM only honors it when the manifest declares
 * {@code Multi-Release: true}, and the jar plugin does not add that attribute
 * automatically. This goal rewrites the finished jar in place.
 *
 * <p>It relies on the default {@code maven-jar-plugin:jar} execution running
 * first within the {@code package} phase (a packaging's default lifecycle
 * bindings run before additionally declared plugin executions in the same
 * phase). If you re-bind the jar goal to a custom execution, declare
 * {@code maven-jar-plugin} before this plugin.
 *
 * <p>When no versioned classes were produced (for example because Maven runs on
 * a JDK older than 28), the jar is left untouched.
 */
@Mojo(name = "jar", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true, requiresProject = true)
public class MultiReleaseJarMojo extends AbstractMojo {

    /** Skip rewriting the jar. */
    @Parameter(defaultValue = "false", property = "auto-valhalla.skip")
    private boolean skip;

    /** Whether to actually add the multi-release declaration. */
    @Parameter(defaultValue = "true")
    private boolean multiRelease;

    /** The jar produced by {@code maven-jar-plugin}. */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    @Parameter(defaultValue = "${project.build.finalName}", readonly = true, required = true)
    private String finalName;

    @Parameter(defaultValue = "${project.packaging}", readonly = true, required = true)
    private String packaging;

    /** The compiled classes directory, to check whether versioned classes were
     *  produced by the {@code transform} goal. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File outputDirectory;

    /** The current Maven project, used to look up the maven-jar-plugin
     *  version in use. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The current Maven session, so the jar-plugin version check runs at
     *  most once per Maven run across all modules and goals. */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("auto-valhalla: skipping jar manifest rewrite");
            return;
        }
        if (!"jar".equals(packaging)) {
            getLog().info("auto-valhalla: packaging '" + packaging + "' is not jar; skipping");
            return;
        }
        if (!new File(outputDirectory, "META-INF/versions").isDirectory()) {
            getLog().info("auto-valhalla: no META-INF/versions produced; jar is not multi-release");
            return;
        }
        JarPluginCheck.checkOnce(session, project, getLog());
        if (!multiRelease) {
            return;
        }
        String jarName = finalName;
        if (!jarName.endsWith(".jar")) {
            jarName = jarName + ".jar";
        }
        File jar = new File(buildDirectory, jarName);
        if (!jar.isFile()) {
            throw new MojoFailureException("auto-valhalla: cannot turn " + jar.getAbsolutePath()
                    + " into a multi-release jar: the jar does not exist. Ensure the "
                    + "maven-jar-plugin's jar goal ran before this goal."
                    + (packaging.isEmpty()
                            ? " (the project might use a non-jar packaging)" : ""));
        }
        boolean updated;
        try {
            updated = MultiReleaseJar.addMultiReleaseFlag(jar);
        } catch (IOException e) {
            throw new MojoExecutionException("auto-valhalla: failed to rewrite "
                    + jar.getAbsolutePath() + " as a multi-release jar: " + e.getMessage(), e);
        }
        if (updated) {
            getLog().info("auto-valhalla: added Multi-Release: true to " + jar.getName());
        } else {
            getLog().info("auto-valhalla: " + jar.getName() + " already has Multi-Release: true");
        }
    }
}
