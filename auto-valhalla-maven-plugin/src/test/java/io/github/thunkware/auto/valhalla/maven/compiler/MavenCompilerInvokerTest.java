package io.github.thunkware.auto.valhalla.maven.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class MavenCompilerInvokerTest {

    @Test
    void mergesCompilerConfigurationsOpaquelyInOriginOrder() {
        XmlPlexusConfiguration nested = compilerConfig(
                "parameters", "true",
                "debug", "true",
                "debuglevel", "source,lines",
                "compilerArgs/arg", "-Xlint:unchecked",
                "release", "17");
        XmlPlexusConfiguration project = compilerConfig(
                "parameters", "false",
                "showWarnings", "false",
                "showDeprecation", "true",
                "release", "21");

        Xpp3Dom root = new Xpp3Dom("configuration");
        MavenCompilerInvoker.mergeCompilerConfigurations(Arrays.asList(nested, project), root);

        // Earlier sources win for duplicate keys (parameters, release is owned).
        assertEquals("true", childValue(root, "parameters"));
        assertEquals("true", childValue(root, "debug"));
        assertEquals("source,lines", childValue(root, "debuglevel"));
        assertEquals("false", childValue(root, "showWarnings"));
        assertEquals("true", childValue(root, "showDeprecation"));
        assertEquals("-Xlint:unchecked", childValue(root, "compilerArgs", "arg"));
        // Owned keys never leak from user configuration.
        assertNull(childValue(root, "release"));
    }

    @Test
    void compilerArgsPassesThroughWhenUserSuppliedOnly() {
        XmlPlexusConfiguration project = new XmlPlexusConfiguration("configuration");
        XmlPlexusConfiguration compilerArgs = new XmlPlexusConfiguration("compilerArgs");
        XmlPlexusConfiguration arg = new XmlPlexusConfiguration("arg");
        arg.setValue("-Werror");
        compilerArgs.addChild(arg);
        project.addChild(compilerArgs);

        Xpp3Dom root = new Xpp3Dom("configuration");
        MavenCompilerInvoker.mergeCompilerConfigurations(Collections.singletonList(project), root);

        assertEquals("-Werror", childValue(root, "compilerArgs", "arg"));
    }

    private static XmlPlexusConfiguration compilerConfig(String... entries) {
        XmlPlexusConfiguration config = new XmlPlexusConfiguration("configuration");
        for (int i = 0; i < entries.length; i += 2) {
            String name = entries[i];
            String value = entries[i + 1];
            if (name.contains("/")) {
                String[] path = name.split("/", 2);
                XmlPlexusConfiguration outer = new XmlPlexusConfiguration(path[0]);
                XmlPlexusConfiguration inner = new XmlPlexusConfiguration(path[1]);
                inner.setValue(value);
                outer.addChild(inner);
                config.addChild(outer);
            } else {
                config.addChild(name, value);
            }
        }
        return config;
    }

    private static String childValue(Xpp3Dom parent, String... path) {
        Xpp3Dom node = parent;
        for (String segment : path) {
            node = node.getChild(segment);
            if (node == null) {
                return null;
            }
        }
        return node.getValue();
    }
}
