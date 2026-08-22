package io.github.thunkware.auto.valhalla.maven;

import static io.github.thunkware.auto.valhalla.maven.Utils.asBoolean;
import static io.github.thunkware.auto.valhalla.maven.Utils.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.Utils.normalizeEncoding;
import static io.github.thunkware.auto.valhalla.maven.Utils.trim;

import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
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
@Mojo(name = "transform", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class TransformMojo extends AbstractMojo {

    /**
     * Minimum Java feature version that can compile value classes.
     */
    static final int MIN_VALHALLA_JDK = 28;

    static final int MIN_MAVEN_JDK = 8;

    /**
     * Skip the transformation entirely.
     */
    @Parameter(defaultValue = "false", property = "auto-valhalla.skip")
    private boolean skip;

    /**
     * Whether to fork the {@code javac} executable (the default) or compile
     * in-process through the {@code javax.tools.JavaCompiler} API. When
     * false, the {@code javac} executable override is ignored and the JDK
     * running Maven does the compiling.
     */
    @Parameter(defaultValue = "true", property = "auto-valhalla.fork")
    private boolean fork;

    /**
     * Whether to skip the annotation-processor selection pass and reuse the
     * generated dir from a previous run (e.g. a prior {@code process-sources}
     * execution or manually generated sources under
     * {@code target/auto-valhalla-generated-sources/selected}); only what that manifest
     * lists is compiled.
     */
    @Parameter(defaultValue = "false", property = "auto-valhalla.skipProcessor")
    private boolean skipProcessor;

    /**
     * The compiled classes directory; the versioned value classes are written
     * under {@code META-INF/versions/<versionDirectory>} here.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File outputDirectory;

    /**
     * Maven's {@code target} directory, used for the generated dir.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    /**
     * Fail the build when an annotation-selected class cannot be compiled as a
     * value class. Mirrors the agent's {@code annotation.rejected}/
     * {@code annotation.fail} defaulting to {@code fatal}.
     */
    @Parameter(defaultValue = "true")
    private boolean failOnAnnotationFailure;

    /**
     * Override for the JDK compiler executable. When Maven runs on JDK 8
     * through 27, {@code JAVA28_HOME} must point to the JDK 28 compiler; on
     * JDK 28, the running JDK compiler is used by default.
     */
    @Parameter(property = "auto-valhalla.javac")
    private String javac;

    /**
     * Character encoding for source compilation. Defaults to
     * {@code ${project.build.sourceEncoding}} or UTF-8.
     */
    @Parameter(property = "auto-valhalla.encoding", defaultValue = "${project.build.sourceEncoding}")
    private String encoding;

    /**
     * Whether to generate metadata for reflection on method parameters
     * ({@code -parameters}). If not explicitly specified, inherits from
     * {@code maven-compiler-plugin} if configured there.
     */
    @Parameter(property = "auto-valhalla.parameters")
    private Boolean parameters;

    /**
     * Whether to include debugging information in the compiled class files
     * ({@code -g} or {@code -g:none}). If not explicitly specified, inherits
     * from {@code maven-compiler-plugin}.
     */
    @Parameter(property = "auto-valhalla.debug")
    private Boolean debug;

    /**
     * Keyword list to be appended to the {@code -g} command-line switch
     * (e.g. {@code lines,vars,source}).
     */
    @Parameter(property = "auto-valhalla.debuglevel")
    private String debuglevel;

    /**
     * Whether to show or suppress compiler warnings ({@code -nowarn} when false).
     */
    @Parameter(property = "auto-valhalla.showWarnings")
    private Boolean showWarnings;

    /**
     * Whether to show deprecation warnings ({@code -deprecation} when true).
     */
    @Parameter(property = "auto-valhalla.showDeprecation")
    private Boolean showDeprecation;

    /**
     * A list of additional compiler arguments to pass to javac (e.g.
     * {@code <compilerArgs><arg>-parameters</arg></compilerArgs>}).
     */
    @Parameter
    private List<String> compilerArgs;

    /**
     * A single additional compiler argument to pass to javac.
     */
    @Parameter(property = "auto-valhalla.compilerArgument")
    private String compilerArgument;

    /**
     * Nested compiler configuration block (e.g. {@code <maven-compiler>} or {@code <compiler>}).
     */
    @Parameter(alias = "compiler")
    private CompilerConfiguration mavenCompiler;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The current Maven session, so the jar-plugin version check runs at
     * most once per Maven run across all modules and goals.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("auto-valhalla: skipping transformation");
            return;
        }
        JarPluginCheck.checkOnce(session, project, getLog());
        JdkVersion.validate();
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
        String resolvedEncoding = resolveEncoding();
        List<String> extraCompilerArgs = resolveCompilerArgs();
        Input input = Input.builder()
                .sourceRoots(project.getCompileSourceRoots())
                .outputDirectory(outputDirectory)
                .buildDirectory(buildDirectory)
                .javac(javacExecutable())
                .processorPath(processorPath)
                .compileClasspath(compileClasspath)
                .encoding(resolvedEncoding)
                .compilerArgs(extraCompilerArgs)
                .fork(fork)
                .skipProcessor(skipProcessor)
                .build();
        Result result;
        try {
            result = AutoValhallaSourceTransformer.transform(input);
        } catch (IOException e) {
            throw new MojoExecutionException("auto-valhalla: failed during the source-level "
                    + "transformation: " + e.getMessage(), e);
        }

        for (String failure : result.annotationFailures()) {
            getLog().error("auto-valhalla: " + failure
                    + "; leaving as an identity class");
        }
        if (!result.annotationFailures().isEmpty() && failOnAnnotationFailure) {
            throw new MojoFailureException("auto-valhalla: " + result.annotationFailures().size()
                    + " annotation-selected class(es) could not be compiled as value classes:\n  - "
                    + String.join("\n  - ", result.annotationFailures()));
        }
        if (result.convertedCount() > 0) {
            getLog().info("auto-valhalla: compiled " + result.convertedCount()
                    + " class(es) into value classes under META-INF/versions/" + MIN_VALHALLA_JDK);
        } else {
            getLog().info("auto-valhalla: no classes converted into value classes");
        }
    }

    private <E, T> Supplier<T> resolve(E bean, Function<E, T> getter) {
        return () -> bean == null ? null : getter.apply(bean);
    }

    List<String> resolveCompilerArgs() {
        List<String> args = new ArrayList<>();
        Xpp3Dom compilerConfig = getCompilerPluginConfiguration();

        Boolean resolvedParameters = firstNonNull(
                this.parameters,
                resolve(mavenCompiler, CompilerConfiguration::getParameters),
                resolveBoolean(compilerConfig, "parameters"));

        Boolean resolvedDebug = firstNonNull(
                this.debug,
                resolve(mavenCompiler, CompilerConfiguration::getDebug),
                resolveBoolean(compilerConfig, "debug"));

        String resolvedDebuglevel = firstNonEmpty(
                this.debuglevel,
                resolve(mavenCompiler, CompilerConfiguration::getDebuglevel),
                resolveString(compilerConfig, "debuglevel"));

        Boolean resolvedShowWarnings = firstNonNull(
                this.showWarnings,
                resolve(mavenCompiler, CompilerConfiguration::getShowWarnings),
                resolveBoolean(compilerConfig, "showWarnings"));

        Boolean resolvedShowDeprecation = firstNonNull(
                this.showDeprecation,
                resolve(mavenCompiler, CompilerConfiguration::getShowDeprecation),
                resolveBoolean(compilerConfig, "showDeprecation"));

        String resolvedCompilerArgument = firstNonEmpty(
                this.compilerArgument,
                resolve(mavenCompiler, CompilerConfiguration::getCompilerArgument),
                resolveString(compilerConfig, "compilerArgument"));

        List<String> resolvedCompilerArgs = firstNonEmptyList(
                this.compilerArgs,
                resolve(mavenCompiler, CompilerConfiguration::getCompilerArgs),
                () -> resolveCompilerArgsList(compilerConfig));

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
        if (resolvedCompilerArgs != null) {
            for (String arg : resolvedCompilerArgs) {
                if (isNotBlank(arg)) {
                    args.add(trim(arg));
                }
            }
        }
        return args;
    }

    String resolveEncoding() {
        Xpp3Dom compilerConfig = getCompilerPluginConfiguration();
        String enc = firstNonEmpty(
                this.encoding,
                resolve(mavenCompiler, CompilerConfiguration::getEncoding),
                resolveString(compilerConfig, "encoding"));
        return normalizeEncoding(enc);
    }

    private static <T> T firstNonNull(T a, Supplier<T> bSupplier, Supplier<T> cSupplier) {
        if (a != null) {
            return a;
        }
        T b = bSupplier.get();
        if (b != null) {
            return b;
        }
        return cSupplier.get();
    }

    private static String firstNonEmpty(String a, Supplier<String> bSupplier, Supplier<String> cSupplier) {
        if (isNotBlank(a)) {
            return trim(a);
        }
        String b = bSupplier.get();
        if (isNotBlank(b)) {
            return trim(b);
        }
        String c = cSupplier.get();
        if (isNotBlank(c)) {
            return trim(c);
        }
        return null;
    }

    private static List<String> firstNonEmptyList(List<String> a, Supplier<List<String>> bSupplier, Supplier<List<String>> cSupplier) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        List<String> b = bSupplier.get();
        if (b != null && !b.isEmpty()) {
            return b;
        }
        return cSupplier.get();
    }

    private Xpp3Dom getCompilerPluginConfiguration() {
        if (project == null) {
            return null;
        }
        org.apache.maven.model.Plugin plugin = project.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (plugin == null) {
            plugin = project.getPlugin("maven-compiler-plugin");
        }
        if (plugin != null && plugin.getConfiguration() instanceof Xpp3Dom) {
            return (Xpp3Dom) plugin.getConfiguration();
        }
        return null;
    }

    private static List<String> resolveCompilerArgsList(Xpp3Dom compilerConfig) {
        if (compilerConfig == null) {
            return Collections.emptyList();
        }
        Xpp3Dom argsDom = compilerConfig.getChild("compilerArgs");
        if (argsDom == null) {
            argsDom = compilerConfig.getChild("compilerArguments");
        }
        if (argsDom != null) {
            List<String> list = new ArrayList<>();
            for (Xpp3Dom child : argsDom.getChildren()) {
                String val = child.getValue();
                if (isNotBlank(val)) {
                    list.add(trim(val));
                } else if (child.getName() != null && child.getName().startsWith("-")) {
                    list.add(child.getName());
                }
            }
            if (!list.isEmpty()) {
                return list;
            }
        }
        return Collections.emptyList();
    }

    private static Supplier<Boolean> resolveBoolean(Xpp3Dom compilerConfig, String childName) {
        if (compilerConfig != null) {
            return () -> {
                Xpp3Dom child = compilerConfig.getChild(childName);
                String value = getDomValue(child);
                return value == null ? null : Boolean.valueOf(value);
            };
        }
        return () -> null;
    }

    private static Supplier<String> resolveString(Xpp3Dom compilerConfig, String childName) {
        if (compilerConfig != null) {
            return () -> {
                Xpp3Dom child = compilerConfig.getChild(childName);
                return getDomValue(child);
            };
        }
        return () -> null;
    }

    private static String getDomValue(Xpp3Dom child) {
        if (child != null && child.getValue() != null && !child.getValue().trim().isEmpty()) {
            return child.getValue().trim();
        }
        return null;
    }

    void setProject(MavenProject project) {
        this.project = project;
    }

    void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    void setParameters(Boolean parameters) {
        this.parameters = parameters;
    }

    void setDebug(Boolean debug) {
        this.debug = debug;
    }

    void setDebuglevel(String debuglevel) {
        this.debuglevel = debuglevel;
    }

    void setShowWarnings(Boolean showWarnings) {
        this.showWarnings = showWarnings;
    }

    void setShowDeprecation(Boolean showDeprecation) {
        this.showDeprecation = showDeprecation;
    }

    void setCompilerArgs(List<String> compilerArgs) {
        this.compilerArgs = compilerArgs;
    }

    void setCompilerArgument(String compilerArgument) {
        this.compilerArgument = compilerArgument;
    }

    void setMavenCompiler(CompilerConfiguration mavenCompiler) {
        this.mavenCompiler = mavenCompiler;
    }

    private String javacExecutable() {
        return Javac.resolveExecutable(javac, MIN_VALHALLA_JDK);
    }

    /**
     * The Java feature version of the current JVM (28 for JDK 28), parsed from
     * the specification version so it works on every JDK.
     */
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
