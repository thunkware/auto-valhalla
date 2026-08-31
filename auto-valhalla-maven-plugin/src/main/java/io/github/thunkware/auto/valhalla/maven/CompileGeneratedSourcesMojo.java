package io.github.thunkware.auto.valhalla.maven;

import static io.github.thunkware.auto.valhalla.maven.support.FileTool.normalizeEncoding;
import static io.github.thunkware.auto.valhalla.maven.support.JdkVersionValidator.MIN_VALHALLA_JDK;
import static io.github.thunkware.auto.valhalla.maven.support.LogTool.info;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.plural;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.trim;
import static org.apache.maven.plugins.annotations.LifecyclePhase.PROCESS_CLASSES;
import static org.apache.maven.plugins.annotations.ResolutionScope.COMPILE_PLUS_RUNTIME;

import io.github.thunkware.auto.valhalla.maven.compiler.AutoValhallaSourceTransformer;
import io.github.thunkware.auto.valhalla.maven.compiler.Javac;
import io.github.thunkware.auto.valhalla.maven.model.MavenCompilerInput;
import io.github.thunkware.auto.valhalla.maven.model.Result;
import io.github.thunkware.auto.valhalla.maven.support.ConfigEvaluator;
import io.github.thunkware.auto.valhalla.maven.support.ConfigOrigin;
import io.github.thunkware.auto.valhalla.maven.support.JarPluginChecker;
import io.github.thunkware.auto.valhalla.maven.support.JdkVersionValidator;
import io.github.thunkware.auto.valhalla.maven.support.MojoTool;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
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
 * Turns {@code @AutoValhalla}-annotated classes into JEP 401 value classes at
 * compile time and writes them under {@code META-INF/versions/<versionDirectory>}
 * so the jar becomes a multi-release jar whose value-class variants are used on
 * JDK 28+ and whose original identity classes are used everywhere else.
 *
 * <p>There is no bytecode rewriting: for every selected source file the goal
 * copies it into a generated dir with its {@code class}/{@code record}
 * declarations generated into {@code value class}/{@code value record}, and then
 * delegates to the JDK compiler ({@code javac --release <N> --enable-preview}),
 * which produces the value-class files natively. The base classes are left
 * untouched, so they keep working on JDKs older than 28.
 *
 * <p>The goal performs no compilation of its own: bind it to the lifecycle after
 * {@code compile} (the default {@code process-classes} phase) so the project's
 * other classes exist when the value classes are compiled; those other classes
 * are linked from {@code target/classes}, which is on the javac classpath.
 *
 * <p>Maven may run on JDK 8 through 27 when {@code JAVA28_HOME} points to a
 * JDK 28 installation. It may also run directly on JDK 28.
 *
 * <p>Selection and failure handling mirror the agent:
 * <ul>
 *   <li>the {@code @AutoValhalla} annotation is the in-source opt-in;</li>
 *   <li>by default an annotated class that javac rejects (because it cannot be
 *       a value class) fails the build ({@code failOnAnnotationFailure}).</li>
 * </ul>
 */
@Mojo(name = "compile-generated-sources",
        defaultPhase = PROCESS_CLASSES,
        threadSafe = true,
        requiresDependencyResolution = COMPILE_PLUS_RUNTIME
)
public class CompileGeneratedSourcesMojo extends AbstractMojo {

    /**
     * Skip the transformation entirely.
     */
    @Parameter(defaultValue = "false", property = "auto-valhalla.skipCompileGeneratedSources")
    private boolean skipCompileGeneratedSources;

    /**
     * Strip the {@code @AutoValhalla} marker (and its import) from the
     * generated value-class sources. Mirrors the {@code generate-sources} goal's
     * parameter so that goal's output is not clobbered when this goal re-runs
     * the selection pass.
     */
    @Parameter(defaultValue = "false", property = "auto-valhalla.removeAnnotation")
    private boolean removeAnnotation;

    /**
     * The compiled classes directory; the versioned value classes are written
     * under {@code META-INF/versions/<versionDirectory>} here.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    protected File outputDirectory;

    /**
     * Maven's {@code target} directory, used for the generated dir.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    protected File buildDirectory;

    /**
     * Nested compiler configuration block (e.g. {@code <maven-compiler>} or {@code <compiler>}).
     */
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
            String test = isTest() ? "test " : "";
            info(getLog(), "skipping compiling generated {}sources", test);
            return;
        }

        JarPluginChecker.checkOnce(session, project, getLog());
        JdkVersionValidator.validate(resolveExecutableOverride());
        List<String> compileClasspath = MojoTool.getCompileClasspath(project, isTest());

        String processorPath = MojoTool.getProcessorPath();
        String resolvedEncoding = resolveEncoding();
        MavenCompilerInput input = MavenCompilerInput.builder()
                .sourceRoots(sourceRoots())
                .outputDirectory(outputDirectory())
                .buildDirectory(buildDirectory)
                .generatedSourcesDirectory(generatedSourcesDirectory())
                .executable(resolveExecutable())
                .processorPath(processorPath)
                .compileClasspath(compileClasspath)
                .encoding(resolvedEncoding)
                .compilerConfigurations(getConfigEvaluator().configurations())
                .release(resolveRelease())
                .enablePreview(resolveEnablePreview())
                .removeAnnotation(removeAnnotation)
                .session(session)
                .project(project)
                .pluginManager(pluginManager)
                .isTest(isTest())
                .build();
        Result result;
        try {
            AutoValhallaSourceTransformer autoValhallaSourceTransformer = new AutoValhallaSourceTransformer(getLog());
            result = autoValhallaSourceTransformer.transform(input);
        } catch (IOException e) {
            throw new MojoExecutionException("auto-valhalla: failed during the source-level "
                    + "transformation: " + e, e);
        }

        int count = result.convertedCount();
        if (count > 0) {
            String simpleVersionsDir = relativeSubDir(buildDirectory, result.versionsDirectory);
            info(getLog(), "compiled {} generated source file{} to {}",
                    count, plural(count), simpleVersionsDir);
        } else {
            info(getLog(), "compiled no generated source files");
        }
    }

    protected boolean isTest() {
        return false;
    }

    static String relativeSubDir(File buildDirectory, File subDir) {
        File parentDir = buildDirectory.getParentFile() == null ? buildDirectory : buildDirectory.getParentFile();
        return removeFirst(subDir.toString(), parentDir + File.separator);
    }

    private static String removeFirst(String input, String substring) {
        return Pattern.compile(substring, Pattern.LITERAL)
                .matcher(input)
                .replaceFirst("");
    }

    private ConfigEvaluator getConfigEvaluator() {
        if (configEvaluatorSupplier == null) {
            configEvaluatorSupplier = ConfigEvaluator.of(project, mavenCompiler, configOrigin);
        }
        return configEvaluatorSupplier.get();
    }

    List<PlexusConfiguration> compilerConfigurations() {
        return getConfigEvaluator().configurations();
    }

    String resolveEncoding() {
        String encoding = getConfigEvaluator().resolveString("encoding");
        return normalizeEncoding(encoding);
    }

    String resolveRelease() {
        String release = getConfigEvaluator().resolveString("release");
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

    boolean resolveEnablePreview() {
        Boolean preview = getConfigEvaluator().resolveBoolean("enablePreview");
        if (preview != null) {
            return preview;
        }
        String property = project.getProperties().getProperty("maven.compiler.enablePreview");
        return isNotBlank(property) && Boolean.parseBoolean(trim(property));
    }

    void setProject(MavenProject project) {
        this.project = project;
    }

    void setMavenCompiler(PlexusConfiguration mavenCompiler) {
        this.mavenCompiler = mavenCompiler;
    }

    void setMavenCompiler(Xpp3Dom mavenCompiler) {
        this.mavenCompiler = mavenCompiler == null ? null : new XmlPlexusConfiguration(mavenCompiler);
    }

    private String resolveExecutableOverride() {
        return getConfigEvaluator().resolveString("executable");
    }

    private String resolveExecutable() {
        return Javac.resolveExecutable(resolveExecutableOverride(), MIN_VALHALLA_JDK);
    }

    protected List<String> sourceRoots() {
        return project.getCompileSourceRoots();
    }

    protected File generatedSourcesDirectory() {
        return new File(buildDirectory, "auto-valhalla-generated-sources");
    }

    protected File outputDirectory() {
        return outputDirectory;
    }

    protected boolean isSkipped() {
        return skipCompileGeneratedSources;
    }

}
