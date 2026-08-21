package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;

/**
 * Turns {@code @AutoValhalla}-annotated classes into JEP 401 value classes at
 * compile time and writes them under {@code META-INF/versions/<versionDirectory>}
 * so the jar becomes a multi-release jar whose value-class variants are used on
 * JDK 28+ and whose original identity classes are used everywhere else.
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
 *   <li>the {@code @AutoValhalla} annotation is the in-source opt-in;</li>
 *   <li>by default an annotated class that javac rejects (because it cannot be
 *       a value class) fails the build ({@code failOnAnnotationFailure}).</li>
 * </ul>
 */
@Mojo(name = "transform", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class TransformMojo extends AbstractMojo {

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

    /** Override for the JDK compiler executable; defaults to
     *  {@code <java.home>/bin/javac}. */
    @Parameter(property = "auto-valhalla.javac")
    private String javac;

    /** Character encoding for source compilation. Defaults to
     *  {@code ${project.build.sourceEncoding}} or UTF-8. */
    @Parameter(property = "auto-valhalla.encoding", defaultValue = "${project.build.sourceEncoding}")
    private String encoding;

    /** Whether to generate metadata for reflection on method parameters
     *  ({@code -parameters}). If not explicitly specified, inherits from
     *  {@code maven-compiler-plugin} if configured there. */
    @Parameter(property = "auto-valhalla.parameters")
    private Boolean parameters;

    /** Whether to include debugging information in the compiled class files
     *  ({@code -g} or {@code -g:none}). If not explicitly specified, inherits
     *  from {@code maven-compiler-plugin}. */
    @Parameter(property = "auto-valhalla.debug")
    private Boolean debug;

    /** Keyword list to be appended to the {@code -g} command-line switch
     *  (e.g. {@code lines,vars,source}). */
    @Parameter(property = "auto-valhalla.debuglevel")
    private String debuglevel;

    /** Whether to show or suppress compiler warnings ({@code -nowarn} when false). */
    @Parameter(property = "auto-valhalla.showWarnings")
    private Boolean showWarnings;

    /** Whether to show deprecation warnings ({@code -deprecation} when true). */
    @Parameter(property = "auto-valhalla.showDeprecation")
    private Boolean showDeprecation;

    /** A list of additional compiler arguments to pass to javac (e.g.
     *  {@code <compilerArgs><arg>-parameters</arg></compilerArgs>}). */
    @Parameter
    private List<String> compilerArgs;

    /** A single additional compiler argument to pass to javac. */
    @Parameter(property = "auto-valhalla.compilerArgument")
    private String compilerArgument;

    /** Nested compiler configuration block (e.g. {@code <maven-compiler>} or {@code <compiler>}). */
    @Parameter(alias = "compiler")
    private CompilerConfiguration mavenCompiler;

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
        Result result;
        try {
            String processorPath = AutoValhallaProcessor.processorPath();
            if (processorPath == null) {
                throw new MojoExecutionException("auto-valhalla: could not locate the "
                        + "auto-valhalla-processor jar for javac's -processorpath");
            }
            String resolvedEncoding = resolveEncoding();
            List<String> extraCompilerArgs = resolveCompilerArgs();
            result = AutoValhallaSourceTransformer.transform(
                    Input.builder()
                            .sourceRoots(project.getCompileSourceRoots())
                            .versionDirectory(versionDirectory)
                            .outputDirectory(outputDirectory)
                            .buildDirectory(buildDirectory)
                            .javac(javacExecutable())
                            .processorPath(processorPath)
                            .compileClasspath(compileClasspath)
                            .encoding(resolvedEncoding)
                            .compilerArgs(extraCompilerArgs)
                            .build());
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
                    + " class(es) into value classes under META-INF/versions/" + versionDirectory);
        } else {
            getLog().info("auto-valhalla: no classes converted into value classes");
        }
    }

    List<String> resolveCompilerArgs() {
        List<String> args = new ArrayList<String>();
        Xpp3Dom compilerConfig = getCompilerPluginConfiguration();

        Boolean resolvedParameters = firstNonNull(
                this.parameters,
                mavenCompiler != null ? mavenCompiler.getParameters() : null,
                resolveBoolean(compilerConfig, "parameters"));

        Boolean resolvedDebug = firstNonNull(
                this.debug,
                mavenCompiler != null ? mavenCompiler.getDebug() : null,
                resolveBoolean(compilerConfig, "debug"));

        String resolvedDebuglevel = firstNonEmpty(
                this.debuglevel,
                mavenCompiler != null ? mavenCompiler.getDebuglevel() : null,
                resolveString(compilerConfig, "debuglevel"));

        Boolean resolvedShowWarnings = firstNonNull(
                this.showWarnings,
                mavenCompiler != null ? mavenCompiler.getShowWarnings() : null,
                resolveBoolean(compilerConfig, "showWarnings"));

        Boolean resolvedShowDeprecation = firstNonNull(
                this.showDeprecation,
                mavenCompiler != null ? mavenCompiler.getShowDeprecation() : null,
                resolveBoolean(compilerConfig, "showDeprecation"));

        String resolvedCompilerArgument = firstNonEmpty(
                this.compilerArgument,
                mavenCompiler != null ? mavenCompiler.getCompilerArgument() : null,
                resolveString(compilerConfig, "compilerArgument"));

        List<String> resolvedCompilerArgs = firstNonEmptyList(
                this.compilerArgs,
                mavenCompiler != null ? mavenCompiler.getCompilerArgs() : null,
                resolveCompilerArgsList(compilerConfig));

        if (resolvedParameters != null && resolvedParameters.booleanValue()) {
            args.add("-parameters");
        }
        if (resolvedDebug != null) {
            if (resolvedDebug.booleanValue()) {
                if (resolvedDebuglevel != null && !resolvedDebuglevel.trim().isEmpty()) {
                    args.add("-g:" + resolvedDebuglevel.trim());
                } else {
                    args.add("-g");
                }
            } else {
                args.add("-g:none");
            }
        } else if (resolvedDebuglevel != null && !resolvedDebuglevel.trim().isEmpty()) {
            args.add("-g:" + resolvedDebuglevel.trim());
        }
        if (resolvedShowWarnings != null && !resolvedShowWarnings.booleanValue()) {
            args.add("-nowarn");
        }
        if (resolvedShowDeprecation != null && resolvedShowDeprecation.booleanValue()) {
            args.add("-deprecation");
        }
        if (resolvedCompilerArgument != null && !resolvedCompilerArgument.trim().isEmpty()) {
            for (String token : resolvedCompilerArgument.trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    args.add(token);
                }
            }
        }
        if (resolvedCompilerArgs != null) {
            for (String arg : resolvedCompilerArgs) {
                if (arg != null && !arg.trim().isEmpty()) {
                    args.add(arg.trim());
                }
            }
        }
        return args;
    }

    String resolveEncoding() {
        Xpp3Dom compilerConfig = getCompilerPluginConfiguration();
        String enc = firstNonEmpty(
                this.encoding,
                mavenCompiler != null ? mavenCompiler.getEncoding() : null,
                resolveString(compilerConfig, "encoding"));
        return (enc != null && !enc.trim().isEmpty()) ? enc.trim() : "UTF-8";
    }

    private static Boolean firstNonNull(Boolean a, Boolean b, Boolean c) {
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return c;
    }

    private static String firstNonEmpty(String a, String b, String c) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        if (c != null && !c.trim().isEmpty()) {
            return c.trim();
        }
        return null;
    }

    private static List<String> firstNonEmptyList(List<String> a, List<String> b, List<String> c) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        if (b != null && !b.isEmpty()) {
            return b;
        }
        return c;
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
            return null;
        }
        Xpp3Dom argsDom = compilerConfig.getChild("compilerArgs");
        if (argsDom == null) {
            argsDom = compilerConfig.getChild("compilerArguments");
        }
        if (argsDom != null) {
            List<String> list = new ArrayList<String>();
            for (Xpp3Dom child : argsDom.getChildren()) {
                String val = child.getValue();
                if (val != null && !val.trim().isEmpty()) {
                    list.add(val.trim());
                } else if (child.getName() != null && child.getName().startsWith("-")) {
                    list.add(child.getName());
                }
            }
            if (!list.isEmpty()) {
                return list;
            }
        }
        return null;
    }

    private static Boolean resolveBoolean(Xpp3Dom compilerConfig, String childName) {
        if (compilerConfig != null) {
            Xpp3Dom child = compilerConfig.getChild(childName);
            if (child != null && child.getValue() != null && !child.getValue().trim().isEmpty()) {
                return Boolean.valueOf(child.getValue().trim());
            }
        }
        return null;
    }

    private static String resolveString(Xpp3Dom compilerConfig, String childName) {
        if (compilerConfig != null) {
            Xpp3Dom child = compilerConfig.getChild(childName);
            if (child != null && child.getValue() != null && !child.getValue().trim().isEmpty()) {
                return child.getValue().trim();
            }
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
