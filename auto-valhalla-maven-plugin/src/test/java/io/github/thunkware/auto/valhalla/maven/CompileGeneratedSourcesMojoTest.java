package io.github.thunkware.auto.valhalla.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thunkware.auto.valhalla.maven.support.JdkVersionValidator;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class CompileGeneratedSourcesMojoTest {

    @Test
    void allowsOlderMavenJdksWhenJava28HomeIsSet() throws Exception {
        JdkVersionValidator.validate(8, "/jdk28");
        JdkVersionValidator.validate(27, "/jdk28");
        JdkVersionValidator.validate(28, null);
    }

    @Test
    void rejectsOlderMavenJdksWithoutJava28Home() {
        assertThrows(MojoFailureException.class,
                () -> JdkVersionValidator.validate(27, null));
    }

    @Test
    void rejectsJdksNewerThanTwentyEight() {
        assertThrows(MojoFailureException.class,
                () -> JdkVersionValidator.validate(29, null));
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

        CompileGeneratedSourcesMojo mojo = new CompileGeneratedSourcesMojo();
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
    void resolvesNestedMavenCompilerConfiguration() {
        Xpp3Dom compiler = nestedCompiler(
                "parameters", "true",
                "debug", "true",
                "debuglevel", "lines,vars",
                "showWarnings", "false",
                "showDeprecation", "true",
                "compilerArgument", "-Xlint:all",
                "encoding", "ISO-8859-1");
        Xpp3Dom compilerArgs = new Xpp3Dom("compilerArgs");
        Xpp3Dom arg = new Xpp3Dom("arg");
        arg.setValue("-Werror");
        compilerArgs.addChild(arg);
        compiler.addChild(compilerArgs);

        CompileGeneratedSourcesMojo mojo = new CompileGeneratedSourcesMojo();
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

        Xpp3Dom compiler = nestedCompiler("parameters", "true", "encoding", "ISO-8859-1");

        CompileGeneratedSourcesMojo mojo = new CompileGeneratedSourcesMojo();
        mojo.setProject(project);
        mojo.setMavenCompiler(compiler);

        List<String> args = mojo.resolveCompilerArgs();
        assertTrue(args.contains("-parameters"));
        assertEquals("ISO-8859-1", mojo.resolveEncoding());
    }

    private static Xpp3Dom nestedCompiler(String... entries) {
        Xpp3Dom compiler = new Xpp3Dom("compiler");
        for (int i = 0; i < entries.length; i += 2) {
            Xpp3Dom child = new Xpp3Dom(entries[i]);
            child.setValue(entries[i + 1]);
            compiler.addChild(child);
        }
        return compiler;
    }
}
