package io.github.thunkware.auto.valhalla.maven.compiler;

import static io.github.thunkware.auto.valhalla.maven.support.FileTool.walk;
import static io.github.thunkware.auto.valhalla.maven.support.LogTool.debug;
import static io.github.thunkware.auto.valhalla.maven.support.LogTool.info;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.trim;
import static io.github.thunkware.auto.valhalla.maven.support.Undocumented.undocumented;

import io.github.thunkware.auto.valhalla.maven.model.MavenCompilerInput;
import io.github.thunkware.auto.valhalla.maven.model.ProcessResult;
import io.github.thunkware.auto.valhalla.maven.support.Failable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Compiles generated sources by invoking the consuming project's
 * {@code maven-compiler-plugin}. The generated source directory and versioned
 * output directory are supplied as execution-local configuration.
 */
public final class MavenCompilerInvoker {

    private static final String GROUP_ID = "org.apache.maven.plugins";
    private static final String ARTIFACT_ID = "maven-compiler-plugin";
    private static final String DEFAULT_VERSION = "3.15.0";
    public static final String COMPILE_CLASSPATH_ELEMENTS = "${project.compileClasspathElements}";
    public static final String TEST_CLASSPATH_ELEMENTS = "${project.testClasspathElements}";

    private final Log log;

    public MavenCompilerInvoker(Log log) {
        this.log = log;
    }

    ProcessResult compile(MavenCompilerInput input) {
        MavenSession session = input.session();
        MavenProject project = input.project();
        BuildPluginManager pluginManager = input.pluginManager();
        Plugin plugin = project.getPlugin(GROUP_ID + ":" + ARTIFACT_ID);
        if (plugin == null) {
            plugin = new Plugin();
            plugin.setGroupId(GROUP_ID);
            plugin.setArtifactId(ARTIFACT_ID);
            plugin.setVersion(DEFAULT_VERSION);
        } else if (plugin.getVersion() == null || plugin.getVersion().trim().isEmpty()) {
            plugin.setVersion(DEFAULT_VERSION);
        }

        MavenCompilerLogInterceptor logInterceptor = new MavenCompilerLogInterceptor(log);
        // The compiler mojo rewrites projectArtifact.getFile() to its (versioned)
        // outputDirectory; restore the previous artifact file so downstream reactor
        // modules resolve target/classes instead of a META-INF/versions subdirectory.
        File artifactFile = project.getArtifact() == null ? null : project.getArtifact().getFile();
        try {
            String goal = input.isTest() ? "testCompile" : "compile";
            MojoDescriptor descriptor = pluginManager.getMojoDescriptor(
                    plugin, goal, project.getRemotePluginRepositories(),
                    session.getRepositorySession());
            MojoExecution execution = new MojoExecution(descriptor, "auto-valhalla-generated");
            Xpp3Dom configuration = configuration(input);
            execution.setConfiguration(configuration);

            // The compiler mojo skips ("Nothing to compile - all classes are
            // up to date.") when it finds class files newer than the sources,
            // which a fresh build of the consuming project always is. That
            // short-circuit must never apply here: the annotation-processor
            // selection pass has to run on every build, and the value classes
            // must be recompiled from the regenerated sources. Wiping the
            // output directory (a scratch dir for the selection pass, the
            // META-INF/versions/<N> dir for the value-class compile) keeps the
            // sources permanently stale.
            if (!undocumented("skip-clean") && !input.isTest()) {
                // don't delete for tests. otherwise target/classes are removed when compiling test classes
                deleteRecursively(input.outputDirectory());
            }

            info(log, "Running compiler:{}:{}", DEFAULT_VERSION, goal);
            logInterceptor.installLogInterceptor(pluginManager);
            pluginManager.executeMojo(session, execution);

            return new ProcessResult(0, "");
        } catch (Exception e) {
            debug(log, e);
            return new ProcessResult(1, e.getMessage() == null ? e.toString() : e.getMessage());

        } finally {
            if (project.getArtifact() != null && artifactFile != null) {
                project.getArtifact().setFile(artifactFile);
            }
            logInterceptor.cleanUp();
        }
    }

    private Xpp3Dom configuration(MavenCompilerInput input) {
        Xpp3Dom root = new Xpp3Dom("configuration");
        // The consuming project's nested <compiler> block is merged first and
        // the mandatory settings below are appended after, so that duplicate
        // keys (release, proc, outputDirectory, ...) default to the value-class
        // pass instead of a user mirroring e.g. <release>17</release>.
        if (input.compilerConfiguration() != null) {
            for (PlexusConfiguration child : input.compilerConfiguration().getChildren()) {
                root.addChild(copy(child));
            }
        }
        child(root, "basedir", "${project.basedir}");
        child(root, "buildDirectory", "${project.build.directory}");
        child(root, "project", "${project}");
        child(root, "session", "${session}");
        if (!input.isTest()) {
            child(root, "projectArtifact", "${project.artifact}");
        }

        if (undocumented("dynamically-resolve-compile-path")) {
            configureCompilePath(input, root);
        } else if (undocumented("simple-compile-path") || !input.isTest()) {
            child(root, "compilePath", COMPILE_CLASSPATH_ELEMENTS);
        } else {
            configureCompilePath(input, root);
        }

        child(root, "mojoExecution", "${mojoExecution}");
        Xpp3Dom sourceRoots = new Xpp3Dom("compileSourceRoots");
        for (String sourceRoot : input.sourceRoots()) {
            child(sourceRoots, "compileSourceRoot", sourceRoot);
        }
        root.addChild(sourceRoots);
        child(root, "outputDirectory", input.outputDirectory().getAbsolutePath());
        if (input.release() != null) {
            child(root, "release", input.release());
        }
        if (input.enablePreview()) {
            child(root, "enablePreview", "true");
        }
        if (input.proc() != null) {
            child(root, "proc", input.proc());
        }
        child(root, "compilerId", "javac");
        child(root, "fork", "true");
        child(root, "executable", input.executable());
        child(root, "encoding", input.encoding());
        child(root, "useIncrementalCompilation", "false");
        child(root, "forceLegacyJavacApi", "true");

        Xpp3Dom args = new Xpp3Dom("compilerArgs");
        for (String value : input.compilerArgs()) {
            if (isNotBlank(value)) {
                child(args, "arg", trim(value));
            }
        }
        root.addChild(args);
        debug(log, "maven-compiler configuration: {}", root);
        return root;
    }

    private static void configureCompilePath(MavenCompilerInput input, Xpp3Dom root) {
        // Use the resolved input classpath (the test mojo supplies test
        // elements, so generated test value classes can see JUnit and
        // target/test-classes) instead of the project.compileClasspathElements
        // expression, which only ever resolves to the main compile classpath.
        List<String> elements = input.compileClasspath();
        String configName = input.isTest() ? "testPath" : "compilePath";
        if (elements == null || elements.isEmpty()) {
            String propertyName = input.isTest() ? TEST_CLASSPATH_ELEMENTS : COMPILE_CLASSPATH_ELEMENTS;
            child(root, configName, propertyName);
            return;
        }
        Xpp3Dom compilePath = new Xpp3Dom(configName);
        for (String element : new LinkedHashSet<>(elements)) {
            if (isNotBlank(element)) {
                child(compilePath, "path", trim(element));
            }
        }
        root.addChild(compilePath);
    }

    private static Xpp3Dom child(String name, String value) {
        Xpp3Dom node = new Xpp3Dom(name);
        node.setValue(value);
        return node;
    }

    private void deleteRecursively(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        List<Path> files = new ArrayList<>();
        Failable.run(() -> walk(dir.toPath()).forEach(files::add),
                e -> debug(log, "could not delete {}", dir, e));

        files.stream()
                .sorted(Comparator.reverseOrder())
                .forEach(path ->
                        Failable.run(() -> Files.deleteIfExists(path),
                                e -> debug(log, "could not delete {}", path, e)));
    }

    private static void child(Xpp3Dom parent, String name, String value) {
        parent.addChild(child(name, value));
    }

    private static Xpp3Dom copy(PlexusConfiguration source) {
        Xpp3Dom target = new Xpp3Dom(source.getName());
        target.setValue(source.getValue(null));
        for (PlexusConfiguration child : source.getChildren()) {
            target.addChild(copy(child));
        }
        return target;
    }
}
