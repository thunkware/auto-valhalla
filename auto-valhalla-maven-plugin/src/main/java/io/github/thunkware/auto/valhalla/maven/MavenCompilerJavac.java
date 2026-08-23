package io.github.thunkware.auto.valhalla.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Compiles generated sources by invoking the consuming project's
 * {@code maven-compiler-plugin}. The generated source directory and versioned
 * output directory are supplied as execution-local configuration.
 */
final class MavenCompilerJavac {

    private static final String GROUP_ID = "org.apache.maven.plugins";
    private static final String ARTIFACT_ID = "maven-compiler-plugin";
    private static final String DEFAULT_VERSION = "3.13.0";

    private MavenCompilerJavac() {
        throw new AssertionError();
    }

    static Javac.ProcessResult compile(MavenCompilerInput input) {
        MavenSession session = input.session;
        MavenProject project = input.project;
        BuildPluginManager pluginManager = input.pluginManager;
        Plugin plugin = project.getPlugin(GROUP_ID + ":" + ARTIFACT_ID);
        if (plugin == null) {
            plugin = new Plugin();
            plugin.setGroupId(GROUP_ID);
            plugin.setArtifactId(ARTIFACT_ID);
            plugin.setVersion(DEFAULT_VERSION);
        } else if (plugin.getVersion() == null || plugin.getVersion().trim().isEmpty()) {
            plugin.setVersion(DEFAULT_VERSION);
        }

        try {
            MojoDescriptor descriptor = pluginManager.getMojoDescriptor(
                    plugin, "compile", project.getRemotePluginRepositories(),
                    session.getRepositorySession());
            MojoExecution execution = new MojoExecution(descriptor, "auto-valhalla-generated");
            execution.setConfiguration(configuration(input));
            pluginManager.executeMojo(session, execution);
            return new Javac.ProcessResult(0, "");
        } catch (Exception e) {
            return new Javac.ProcessResult(1, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static Xpp3Dom configuration(MavenCompilerInput input) {
        Xpp3Dom root = new Xpp3Dom("configuration");
        child(root, "basedir", "${project.basedir}");
        child(root, "buildDirectory", "${project.build.directory}");
        child(root, "project", "${project}");
        child(root, "session", "${session}");
        child(root, "projectArtifact", "${project.artifact}");
        child(root, "compilePath", "${project.compileClasspathElements}");
        child(root, "mojoExecution", "${mojoExecution}");
        Xpp3Dom sourceRoots = new Xpp3Dom("compileSourceRoots");
        for (String sourceRoot : input.sourceRoots) {
            child(sourceRoots, "compileSourceRoot", sourceRoot);
        }
        root.addChild(sourceRoots);
        child(root, "outputDirectory", input.outputDirectory.getAbsolutePath());
        if (input.release != null) {
            child(root, "release", input.release);
        }
        if (input.enablePreview) {
            child(root, "enablePreview", "true");
        }
        if (input.proc != null) {
            child(root, "proc", input.proc);
        }
        child(root, "compilerId", "javac");
        child(root, "fork", "true");
        child(root, "executable", input.executable);
        child(root, "encoding", input.encoding);
        child(root, "useIncrementalCompilation", "false");
        child(root, "forceJavacCompilerUse", "true");

        Xpp3Dom args = new Xpp3Dom("compilerArgs");
        for (String value : input.compilerArgs) {
            if (Utils.isNotBlank(value)) {
                child(args, "arg", Utils.trim(value));
            }
        }
        root.addChild(args);
        return root;
    }

    private static Xpp3Dom child(String name, String value) {
        Xpp3Dom node = new Xpp3Dom(name);
        node.setValue(value);
        return node;
    }

    private static void child(Xpp3Dom parent, String name, String value) {
        parent.addChild(child(name, value));
    }
}
