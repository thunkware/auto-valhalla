package io.github.thunkware.auto.valhalla.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class CompileGeneratedSourcesMojoTest {

    @Test
    void inheritsCompilerConfigOpaquelyFromMavenCompilerPlugin() {
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");

        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom parameters = new Xpp3Dom("parameters");
        parameters.setValue("true");
        config.addChild(parameters);

        Xpp3Dom debug = new Xpp3Dom("debug");
        debug.setValue("true");
        config.addChild(debug);

        Xpp3Dom compilerArgs = new Xpp3Dom("compilerArgs");
        Xpp3Dom arg1 = new Xpp3Dom("arg");
        arg1.setValue("-Xlint:unchecked");
        compilerArgs.addChild(arg1);
        config.addChild(compilerArgs);

        Xpp3Dom encoding = new Xpp3Dom("encoding");
        encoding.setValue("UTF-16");
        config.addChild(encoding);

        compilerPlugin.setConfiguration(config);
        Build build = new Build();
        build.addPlugin(compilerPlugin);
        MavenProject project = new MavenProject();
        project.setBuild(build);

        CompileGeneratedSourcesMojo mojo = new CompileGeneratedSourcesMojo();
        mojo.setProject(project);

        // The project's maven-compiler-plugin configuration is applied opaquely,
        // not translated into javac flags by the plugin.
        List<PlexusConfiguration> configurations = mojo.compilerConfigurations();
        assertEquals(1, configurations.size());
        PlexusConfiguration inherited = configurations.get(0);
        assertEquals("true", inherited.getChild("parameters").getValue(null));
        assertEquals("true", inherited.getChild("debug").getValue(null));
        assertEquals("-Xlint:unchecked",
                inherited.getChild("compilerArgs").getChild("arg").getValue(null));
        assertEquals("UTF-16", mojo.getConfigEvaluator().resolveEncoding());
    }

    @Test
    void resolvesNestedMavenCompilerConfiguration() {
        Xpp3Dom compiler = new Xpp3Dom("compiler");
        child(compiler, "debug", "true");
        child(compiler, "debuglevel", "lines,vars");
        child(compiler, "encoding", "ISO-8859-1");

        CompileGeneratedSourcesMojo mojo = new CompileGeneratedSourcesMojo();
        mojo.setMavenCompiler(compiler);
        mojo.setProject(new MavenProject());

        List<PlexusConfiguration> configurations = mojo.compilerConfigurations();
        assertEquals(1, configurations.size());
        PlexusConfiguration nested = configurations.get(0);
        assertEquals("lines,vars", nested.getChild("debuglevel").getValue(null));
        assertEquals("ISO-8859-1", mojo.getConfigEvaluator().resolveEncoding());
    }

    @Test
    void nestedCompilerConfigOverridesInheritedCompilerPluginConfig() {
        MavenProject project = new MavenProject();
        Build build = new Build();
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");

        Xpp3Dom config = new Xpp3Dom("configuration");
        child(config, "parameters", "false");
        child(config, "encoding", "UTF-16");
        compilerPlugin.setConfiguration(config);
        build.addPlugin(compilerPlugin);
        project.setBuild(build);

        Xpp3Dom compiler = new Xpp3Dom("compiler");
        child(compiler, "parameters", "true");
        child(compiler, "encoding", "ISO-8859-1");

        CompileGeneratedSourcesMojo mojo = new CompileGeneratedSourcesMojo();
        mojo.setProject(project);
        mojo.setMavenCompiler(compiler);

        // NESTED_FIRST: the nested block precedes and wins over the project
        // configuration, and both pass through opaquely.
        List<PlexusConfiguration> configurations = mojo.compilerConfigurations();
        assertEquals(2, configurations.size());
        assertEquals("true", configurations.get(0).getChild("parameters").getValue(null));
        assertEquals("false", configurations.get(1).getChild("parameters").getValue(null));
        assertEquals("ISO-8859-1", mojo.getConfigEvaluator().resolveEncoding());
    }

    private static void child(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }
}
