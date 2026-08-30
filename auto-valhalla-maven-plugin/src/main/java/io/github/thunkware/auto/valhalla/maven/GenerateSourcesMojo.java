package io.github.thunkware.auto.valhalla.maven;

import static io.github.thunkware.auto.valhalla.maven.CompileGeneratedSourcesMojo.MIN_VALHALLA_JDK;
import static io.github.thunkware.auto.valhalla.maven.CompileGeneratedSourcesMojo.relativeSubDir;
import static io.github.thunkware.auto.valhalla.maven.support.FileTool.normalizeEncoding;
import static io.github.thunkware.auto.valhalla.maven.support.LogTool.info;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.plural;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.trim;
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
import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.configuration.PlexusConfiguration;

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
    @Parameter(defaultValue = "false", property = "auto-valhalla.skipGenerateSources")
    private boolean skipGenerateSources;

    /**
     * Remove the {@code @AutoValhalla} annotation from the generated source
     * files, so the generated value classes no longer carry the in-source
     * opt-in marker. Only the generated copies are affected; the original
     * sources are never modified. Default {@code false}, i.e. the generated
     * copies keep the annotation.
     */
    @Parameter(defaultValue = "false", property = "auto-valhalla.removeAnnotation")
    private boolean removeAnnotation;

    /**
     * Maven's {@code target} directory, used for the generated sources.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    protected File buildDirectory;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    protected File outputDirectory;

    @Parameter(alias = "compiler")
    private PlexusConfiguration mavenCompiler;

    @Parameter(property = "auto-valhalla.config-origin", defaultValue = "NESTED_FIRST")
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

    private Supplier<ConfigEvaluator> configEvaluatorSupplier;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (isSkipped()) {
            info(getLog(), "skipping source processing");
            return;
        }
        configEvaluatorSupplier = ConfigEvaluator.of(project, mavenCompiler, configOrigin);

        JarPluginChecker.checkOnce(session, project, getLog());
        JdkVersionValidator.validate(resolveExecutableOverride());
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
                    .outputDirectory(outputDirectory)
                    .generatedSourcesDirectory(generatedSourcesDirectory())
                    .executable(resolveExecutable())
                    .processorPath(processorPath)
                    .compileClasspath(compileClasspath)
                    .encoding(resolveEncoding())
                    .compilerConfiguration(mavenCompiler)
                    .release(resolveRelease())
                    .enablePreview(resolveEnablePreview())
                    .removeAnnotation(removeAnnotation)
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
            info(getLog(), "no @AutoValhalla-annotated classes found");
        } else {
            String simpleVersionsDir = relativeSubDir(buildDirectory, selection.generatedSources);
            int count = selection.selectedTypes.size();
            info(getLog(), "generated {} source file{} to {}",
                    count, plural(count), simpleVersionsDir);
        }
    }

    private String resolveEncoding() {
        return normalizeEncoding(firstCompilerValue("encoding"));
    }

    private String resolveRelease() {
        String release = firstCompilerValue("release");
        if (release != null) {
            return release;
        }
        // The plugin also accepts maven.compiler.release / maven.compiler.target
        // properties (e.g. consumers that steer the default-bound compile purely
        // through properties); mirror that so the selection pass compiles at the
        // same level as the base classes.
        release = project.getProperties().getProperty("maven.compiler.release");
        if (isNotBlank(release)) {
            return release;
        }
        String target = project.getProperties().getProperty("maven.compiler.target");
        return isNotBlank(target) ? trim(target) : null;
    }

    private boolean resolveEnablePreview() {
        Boolean preview = firstCompilerBoolean("enablePreview");
        if (preview != null) {
            return preview;
        }
        String property = project.getProperties().getProperty("maven.compiler.enablePreview");
        return isNotBlank(property) && Boolean.parseBoolean(trim(property));
    }

    private String resolveExecutableOverride() {
        return firstCompilerValue("executable");
    }

    private String resolveExecutable() {
        return Javac.resolveExecutable(resolveExecutableOverride(), MIN_VALHALLA_JDK);
    }

    private String firstCompilerValue(String name) {
        return configEvaluatorSupplier.get().resolveString(name);
    }

    private Boolean firstCompilerBoolean(String name) {
        return configEvaluatorSupplier.get().resolveBoolean(name);
    }

    protected List<String> sourceRoots() {
        return project.getCompileSourceRoots();
    }

    protected File generatedSourcesDirectory() {
        return new File(buildDirectory, "auto-valhalla-generated-sources");
    }

    /**
     * The classpath the test source roots are selected against; overridden by
     * the test goal to supply the test classpath so the selection pass can
     * resolve test-only dependencies.
     */
    protected List<String> compileClasspath() throws DependencyResolutionRequiredException {
        return project.getCompileClasspathElements();
    }

    protected boolean isSkipped() {
        return skipGenerateSources;
    }

}
