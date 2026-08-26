package io.github.thunkware.auto.valhalla.maven;

import static io.github.thunkware.auto.valhalla.maven.support.Utils.asBoolean;
import static io.github.thunkware.auto.valhalla.maven.support.Utils.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.support.Utils.normalizeEncoding;
import static io.github.thunkware.auto.valhalla.maven.support.Utils.plural;
import static io.github.thunkware.auto.valhalla.maven.support.Utils.trim;
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
import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
     * Minimum Java feature version that can compile value classes.
     */
    public static final int MIN_VALHALLA_JDK = 28;

    public static final int MIN_MAVEN_JDK = 8;

    /**
     * Skip the transformation entirely.
     */
    private boolean skipCompileGeneratedSources;

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

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (isSkipped()) {
            getLog().info("auto-valhalla: skipping transformation");
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
        String resolvedEncoding = resolveEncoding();
        List<String> extraCompilerArgs = resolveCompilerArgs();
        MavenCompilerInput input = MavenCompilerInput.builder()
                .sourceRoots(sourceRoots())
                .outputDirectory(outputDirectory())
                .buildDirectory(buildDirectory)
                .generatedSourcesDirectory(generatedSourcesDirectory())
                .executable(resolveExecutable())
                .processorPath(processorPath)
                .compileClasspath(compileClasspath)
                .encoding(resolvedEncoding)
                .compilerArgs(extraCompilerArgs)
                .compilerConfiguration(mavenCompiler)
                .session(session)
                .project(project)
                .pluginManager(pluginManager)
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
            getLog().info("auto-valhalla: compiled " + count
                    + " class" + plural(count) + " into value classes under META-INF/versions/" + MIN_VALHALLA_JDK);
        } else {
            getLog().info("auto-valhalla: no classes converted into value classes");
        }
    }

    List<String> resolveCompilerArgs() {
        PlexusConfiguration compilerConfig = getCompilerPluginConfiguration();

        ConfigEvaluator configEvaluator = ConfigEvaluator.of(mavenCompiler, compilerConfig, configOrigin);
        Boolean resolvedParameters = configEvaluator.resolveBoolean("parameters");
        Boolean resolvedDebug = configEvaluator.resolveBoolean("debug");
        String resolvedDebuglevel = configEvaluator.resolveString("debuglevel");
        Boolean resolvedShowWarnings = configEvaluator.resolveBoolean("showWarnings");
        Boolean resolvedShowDeprecation = configEvaluator.resolveBoolean("showDeprecation");
        String resolvedCompilerArgument = configEvaluator.resolveString("compilerArgument");
        List<String> resolvedCompilerArgs = configEvaluator.resolveCompilerArgs();

        List<String> args = new ArrayList<>();
        if (asBoolean(resolvedParameters)) {
            args.add("-parameters");
        }
        if (resolvedDebug != null) {
            if (asBoolean(resolvedDebug)) {
                if (isNotBlank(resolvedDebuglevel)) {
                    args.add("-g:" + trim(resolvedDebuglevel));
                } else {
                    args.add("-g");
                }
            } else {
                args.add("-g:none");
            }
        } else if (isNotBlank(resolvedDebuglevel)) {
            args.add("-g:" + trim(resolvedDebuglevel));
        }
        if (resolvedShowWarnings != null && !asBoolean(resolvedShowWarnings)) {
            args.add("-nowarn");
        }
        if (asBoolean(resolvedShowDeprecation)) {
            args.add("-deprecation");
        }
        if (isNotBlank(resolvedCompilerArgument)) {
            for (String token : trim(resolvedCompilerArgument).split("\\s+")) {
                if (!token.isEmpty()) {
                    args.add(token);
                }
            }
        }
        for (String arg : resolvedCompilerArgs) {
            if (isNotBlank(arg)) {
                args.add(trim(arg));
            }
        }
        return args;
    }

    String resolveEncoding() {
        PlexusConfiguration compilerConfig = getCompilerPluginConfiguration();
        ConfigEvaluator configEvaluator = ConfigEvaluator.of(mavenCompiler, compilerConfig, configOrigin);
        String enc = configEvaluator.resolveString("encoding");
        return normalizeEncoding(enc);
    }

    protected PlexusConfiguration getCompilerPluginConfiguration() {
        if (project == null) {
            return null;
        }
        org.apache.maven.model.Plugin plugin = project.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (plugin == null) {
            plugin = project.getPlugin("maven-compiler-plugin");
        }
        if (plugin != null && plugin.getConfiguration() instanceof Xpp3Dom) {
            return new XmlPlexusConfiguration((Xpp3Dom) plugin.getConfiguration());
        }
        return null;
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

    protected String resolveExecutable() {
        PlexusConfiguration compilerConfig = getCompilerPluginConfiguration();
        String executable = ConfigEvaluator.of(mavenCompiler, compilerConfig, configOrigin)
                .resolveString("executable");
        return Javac.resolveExecutable(executable, MIN_VALHALLA_JDK);
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
        return skipCompileGeneratedSources;
    }

    @Parameter(defaultValue = "false", property = "auto-valhalla.skipCompileGeneratedSources")
    protected void setSkipCompileGeneratedSources(boolean skipCompileGeneratedSources) {
        this.skipCompileGeneratedSources = skipCompileGeneratedSources;
    }

    /**
     * The Java feature version of the current JVM (28 for JDK 28), parsed from
     * the specification version so it works on every JDK.
     */
    public static int jdkFeature() {
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
