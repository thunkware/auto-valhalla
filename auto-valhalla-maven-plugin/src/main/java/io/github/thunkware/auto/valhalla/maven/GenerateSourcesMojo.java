package io.github.thunkware.auto.valhalla.maven;

import static io.github.thunkware.auto.valhalla.maven.support.Utils.plural;
import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;
import static org.apache.maven.plugins.annotations.ResolutionScope.COMPILE_PLUS_RUNTIME;

import io.github.thunkware.auto.valhalla.maven.compiler.AnnotationProcessorRunner;
import io.github.thunkware.auto.valhalla.maven.compiler.Javac;
import io.github.thunkware.auto.valhalla.maven.model.MavenCompilerInput;
import io.github.thunkware.auto.valhalla.maven.model.Selection;
import io.github.thunkware.auto.valhalla.maven.support.ConfigEvaluator;
import io.github.thunkware.auto.valhalla.maven.support.ConfigOrigin;
import io.github.thunkware.auto.valhalla.maven.support.JarPluginChecker;
import io.github.thunkware.auto.valhalla.maven.support.JdkVersionValidator;
import io.github.thunkware.auto.valhalla.maven.support.Utils;
import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.inject.Inject;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Runs only the {@code auto-valhalla} annotation processor: a
 * {@code javac -proc:only} selection pass over the project's source roots that
 * selects the {@code @AutoValhalla}-annotated top-level types and generates
 * generated copies of their source files (with {@code value class}/
 * {@code value record}) under
 * {@code <buildDirectory>/auto-valhalla-generated-sources}.
 *
 * <p>Unlike the {@code transform} goal, nothing is compiled and nothing is
 * written under the project's output directory, so this goal is useful to
 * inspect (or post-process) what would be transformed without producing the
 * multi-release value classes.
 */
@Mojo(name = "generate-sources",
        defaultPhase = GENERATE_SOURCES,
        threadSafe = true,
        requiresDependencyResolution = COMPILE_PLUS_RUNTIME)
public class GenerateSourcesMojo extends AbstractMojo {

    /**
     * Skip the annotation-processor pass entirely.
     */
    private boolean skipGenerateSources;

    /**
     * Maven's {@code target} directory, used for the generated sources.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    protected File buildDirectory;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    protected File outputDirectory;

    @Parameter(alias = "compiler")
    private PlexusConfiguration mavenCompiler;

    @Parameter(property = "auto-valhalla.config-origin", defaultValue = "nestedFirst")
    private ConfigOrigin configOrigin = ConfigOrigin.NESTED_FIRST;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * The current Maven session, so the jar-plugin version check runs at
     * most once per Maven run across all modules and goals.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Inject
    private BuildPluginManager pluginManager;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (isSkipped()) {
            getLog().info("auto-valhalla: skipping source processing");
            return;
        }
        JarPluginChecker.checkOnce(session, project, getLog());
        JdkVersionValidator.validate();
        List<String> compileClasspath;
        try {
            compileClasspath = compileClasspath();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("auto-valhalla: could not resolve the project's "
                    + "compile classpath for javac: " + e.getMessage(), e);
        }
        String processorPath = AutoValhallaProcessor.processorPath();
        if (processorPath == null) {
            throw new MojoExecutionException("auto-valhalla: could not locate the "
                    + "auto-valhalla-processor jar for javac's -processorpath");
        }
        Selection selection;
        try {
            MavenCompilerInput input = MavenCompilerInput.builder()
                    .sourceRoots(sourceRoots())
                    .buildDirectory(buildDirectory)
                    .outputDirectory(outputDirectory())
                    .generatedSourcesDirectory(generatedSourcesDirectory())
                    .executable(resolveExecutable())
                    .processorPath(processorPath)
                    .compileClasspath(compileClasspath)
                    .encoding(resolveEncoding())
                    .compilerConfiguration(mavenCompiler)
                    .session(session)
                    .project(project)
                    .pluginManager(pluginManager)
                    .build();
            AnnotationProcessorRunner runner = new AnnotationProcessorRunner(getLog());
            selection = runner.run(input);
        } catch (IOException e) {
            throw new MojoExecutionException("auto-valhalla: failed during the "
                    + "annotation-processor pass: " + e.getMessage(), e);
        }

        if (selection.selectedTypes.isEmpty()) {
            getLog().info("auto-valhalla: no @AutoValhalla-annotated classes found");
        } else {
            int count = selection.selectedTypes.size();
            getLog().info("auto-valhalla: processed " + count
                    + " @AutoValhalla-annotated class" + plural(count) + "; generated sources under "
                    + selection.generatedSources.getAbsolutePath());
        }
    }

    private String resolveEncoding() {
        return Utils.normalizeEncoding(firstCompilerValue("encoding"));
    }

    private String resolveExecutable() {
        return Javac.resolveExecutable(firstCompilerValue("executable"),
                CompileGeneratedSourcesMojo.MIN_VALHALLA_JDK);
    }

    private String firstCompilerValue(String name) {
        return ConfigEvaluator.of(mavenCompiler, compilerPluginConfiguration(), configOrigin)
                .resolveString(name);
    }

    private PlexusConfiguration compilerPluginConfiguration() {
        Plugin plugin = project.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (plugin == null) {
            plugin = project.getPlugin("maven-compiler-plugin");
        }
        if (plugin != null && plugin.getConfiguration() instanceof Xpp3Dom) {
            return new XmlPlexusConfiguration((Xpp3Dom) plugin.getConfiguration());
        }
        return null;
    }

    protected List<String> sourceRoots() {
        return project.getCompileSourceRoots();
    }

    protected List<String> compileClasspath() throws DependencyResolutionRequiredException {
        return project.getCompileClasspathElements();
    }

    protected File generatedSourcesDirectory() {
        return new File(buildDirectory, "auto-valhalla-generated-sources");
    }

    protected File outputDirectory() {
        return outputDirectory;
    }

    protected boolean isSkipped() {
        return skipGenerateSources;
    }

    @Parameter(defaultValue = "false", property = "auto-valhalla.skipGenerateSources")
    protected void setSkipGenerateSources(boolean skipGenerateSources) {
        this.skipGenerateSources = skipGenerateSources;
    }
}
