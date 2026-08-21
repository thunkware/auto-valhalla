package io.github.thunkware.auto.valhalla.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class TransformMojoTest {

    @Test
    void resolvesDirectParameters() {
        TransformMojo mojo = new TransformMojo();
        mojo.setParameters(true);
        mojo.setDebug(true);
        mojo.setDebuglevel("lines,vars");
        mojo.setShowWarnings(false);
        mojo.setShowDeprecation(true);
        mojo.setCompilerArgument("-Xlint:all");
        mojo.setCompilerArgs(Arrays.asList("-Werror"));
        mojo.setEncoding("ISO-8859-1");

        List<String> args = mojo.resolveCompilerArgs();
        assertTrue(args.contains("-parameters"));
        assertTrue(args.contains("-g:lines,vars"));
        assertTrue(args.contains("-nowarn"));
        assertTrue(args.contains("-deprecation"));
        assertTrue(args.contains("-Xlint:all"));
        assertTrue(args.contains("-Werror"));
        assertEquals("ISO-8859-1", mojo.resolveEncoding());
    }

    @Test
    void resolvesDebugFalseAsGNone() {
        TransformMojo mojo = new TransformMojo();
        mojo.setDebug(false);

        List<String> args = mojo.resolveCompilerArgs();
        assertTrue(args.contains("-g:none"));
    }

    @Test
    void inheritsFromMavenCompilerPlugin() {
        MavenProject project = new MavenProject();
        Build build = new Build();
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

        Xpp3Dom debuglevel = new Xpp3Dom("debuglevel");
        debuglevel.setValue("source,lines");
        config.addChild(debuglevel);

        Xpp3Dom showWarnings = new Xpp3Dom("showWarnings");
        showWarnings.setValue("false");
        config.addChild(showWarnings);

        Xpp3Dom showDeprecation = new Xpp3Dom("showDeprecation");
        showDeprecation.setValue("true");
        config.addChild(showDeprecation);

        Xpp3Dom encoding = new Xpp3Dom("encoding");
        encoding.setValue("UTF-16");
        config.addChild(encoding);

        Xpp3Dom compilerArgs = new Xpp3Dom("compilerArgs");
        Xpp3Dom arg1 = new Xpp3Dom("arg");
        arg1.setValue("-Xlint:unchecked");
        compilerArgs.addChild(arg1);
        config.addChild(compilerArgs);

        compilerPlugin.setConfiguration(config);
        build.addPlugin(compilerPlugin);
        project.setBuild(build);

        TransformMojo mojo = new TransformMojo();
        mojo.setProject(project);

        List<String> args = mojo.resolveCompilerArgs();
        assertTrue(args.contains("-parameters"));
        assertTrue(args.contains("-g:source,lines"));
        assertTrue(args.contains("-nowarn"));
        assertTrue(args.contains("-deprecation"));
        assertTrue(args.contains("-Xlint:unchecked"));
        assertEquals("UTF-16", mojo.resolveEncoding());
    }

    @Test
    void directParametersOverrideInheritedCompilerPluginConfig() {
        MavenProject project = new MavenProject();
        Build build = new Build();
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");

        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom parameters = new Xpp3Dom("parameters");
        parameters.setValue("false");
        config.addChild(parameters);

        Xpp3Dom encoding = new Xpp3Dom("encoding");
        encoding.setValue("UTF-16");
        config.addChild(encoding);

        compilerPlugin.setConfiguration(config);
        build.addPlugin(compilerPlugin);
        project.setBuild(build);

        TransformMojo mojo = new TransformMojo();
        mojo.setProject(project);
        mojo.setParameters(true);
        mojo.setEncoding("UTF-8");

        List<String> args = mojo.resolveCompilerArgs();
        assertTrue(args.contains("-parameters"));
        assertEquals("UTF-8", mojo.resolveEncoding());
    }

    @Test
    void resolvesNestedMavenCompilerConfiguration() {
        CompilerConfiguration compiler = new CompilerConfiguration();
        compiler.setParameters(true);
        compiler.setDebug(true);
        compiler.setDebuglevel("lines,vars");
        compiler.setShowWarnings(false);
        compiler.setShowDeprecation(true);
        compiler.setCompilerArgument("-Xlint:all");
        compiler.setCompilerArgs(Arrays.asList("-Werror"));
        compiler.setEncoding("ISO-8859-1");

        TransformMojo mojo = new TransformMojo();
        mojo.setMavenCompiler(compiler);

        List<String> args = mojo.resolveCompilerArgs();
        assertTrue(args.contains("-parameters"));
        assertTrue(args.contains("-g:lines,vars"));
        assertTrue(args.contains("-nowarn"));
        assertTrue(args.contains("-deprecation"));
        assertTrue(args.contains("-Xlint:all"));
        assertTrue(args.contains("-Werror"));
        assertEquals("ISO-8859-1", mojo.resolveEncoding());
    }

    @Test
    void directParametersOverrideNestedCompilerConfig() {
        CompilerConfiguration compiler = new CompilerConfiguration();
        compiler.setDebug(true);
        compiler.setEncoding("UTF-16");

        TransformMojo mojo = new TransformMojo();
        mojo.setMavenCompiler(compiler);
        mojo.setDebug(false);
        mojo.setEncoding("UTF-8");

        List<String> args = mojo.resolveCompilerArgs();
        assertTrue(args.contains("-g:none"));
        assertEquals("UTF-8", mojo.resolveEncoding());
    }

    @Test
    void nestedCompilerConfigOverridesInheritedCompilerPluginConfig() {
        MavenProject project = new MavenProject();
        Build build = new Build();
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");

        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom parameters = new Xpp3Dom("parameters");
        parameters.setValue("false");
        config.addChild(parameters);

        Xpp3Dom encoding = new Xpp3Dom("encoding");
        encoding.setValue("UTF-16");
        config.addChild(encoding);

        compilerPlugin.setConfiguration(config);
        build.addPlugin(compilerPlugin);
        project.setBuild(build);

        CompilerConfiguration compiler = new CompilerConfiguration();
        compiler.setParameters(true);
        compiler.setEncoding("ISO-8859-1");

        TransformMojo mojo = new TransformMojo();
        mojo.setProject(project);
        mojo.setMavenCompiler(compiler);

        List<String> args = mojo.resolveCompilerArgs();
        assertTrue(args.contains("-parameters"));
        assertEquals("ISO-8859-1", mojo.resolveEncoding());
    }
}
