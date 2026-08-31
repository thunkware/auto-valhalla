package io.github.thunkware.auto.valhalla.maven.support;


import static io.github.thunkware.auto.valhalla.maven.support.StringTool.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.trim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Evaluates nested compiler configuration and project plugin configuration. */
public final class ConfigEvaluator {

    private final PlexusConfiguration[] configurations;

    private ConfigEvaluator(PlexusConfiguration nested, PlexusConfiguration project, ConfigOrigin origin) {
        this.configurations = configurations(origin, nested, project);
    }

    private static String toUpperSnakeCase(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }

    public static Supplier<ConfigEvaluator> of(
            MavenProject project,
            PlexusConfiguration mavenCompiler,
            ConfigOrigin configOrigin) {
        ConfigEvaluator[] ref = new ConfigEvaluator[1];
        return () -> {
            // not thread safe but that's ok.
            if (ref[0] != null) {
                return ref[0];
            }
            PlexusConfiguration compilerConfig = getCompilerPluginConfiguration(project);
            ref[0] = ConfigEvaluator.of(mavenCompiler, compilerConfig, configOrigin);
            return ref[0];
        };
    }

    private static PlexusConfiguration getCompilerPluginConfiguration(MavenProject project) {
        if (project == null) {
            return null;
        }
        Plugin plugin = project.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (plugin == null) {
            plugin = project.getPlugin("maven-compiler-plugin");
        }
        if (plugin != null && plugin.getConfiguration() instanceof Xpp3Dom) {
            return new XmlPlexusConfiguration((Xpp3Dom) plugin.getConfiguration());
        }
        return null;
    }

    static ConfigEvaluator of(
            PlexusConfiguration nested, PlexusConfiguration project, ConfigOrigin origin) {
        return new ConfigEvaluator(nested, project, origin);
    }

    static ConfigEvaluator of(
            PlexusConfiguration nested, PlexusConfiguration project, String origin) {
        return of(nested, project, parseOrigin(origin));
    }

    public Boolean resolveBoolean(String name) {
        String value = resolveString(name);
        return value == null ? null : Boolean.valueOf(value);
    }

    public String resolveString(String name) {
        for (PlexusConfiguration configuration : configurations) {
            String value = value(configuration, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public List<String> resolveCompilerArgs() {
        for (PlexusConfiguration configuration : configurations) {
            List<String> args = compilerArgs(configuration);
            if (!args.isEmpty()) {
                return args;
            }
        }
        return Collections.emptyList();
    }

    private static PlexusConfiguration[] configurations(
            ConfigOrigin origin, PlexusConfiguration nested, PlexusConfiguration project) {
        switch (origin) {
            case PROJECT_FIRST:
                return new PlexusConfiguration[] {project, nested};
            case NESTED_ONLY:
                return new PlexusConfiguration[] {nested};
            case PROJECT_ONLY:
                return new PlexusConfiguration[] {project};
            case NESTED_FIRST:
            default:
                return new PlexusConfiguration[] {nested, project};
        }
    }

    private static ConfigOrigin parseOrigin(String origin) {
        if (origin == null || origin.trim().isEmpty()) {
            return ConfigOrigin.NESTED_FIRST;
        }
        for (ConfigOrigin value : ConfigOrigin.values()) {
            if (value.name().equals(origin) || value.name().equals(toUpperSnakeCase(origin))) {
                return value;
            }
        }
        throw new IllegalArgumentException("config-origin must be one of " + Arrays.toString(ConfigOrigin.values()));
    }

    private static String value(PlexusConfiguration configuration, String name) {
        if (configuration == null) {
            return null;
        }
        PlexusConfiguration child = configuration.getChild(name, false);
        String value = child == null ? null : child.getValue(null);
        return isNotBlank(value) ? trim(value) : null;
    }

    private static List<String> compilerArgs(PlexusConfiguration configuration) {
        if (configuration == null) {
            return Collections.emptyList();
        }
        PlexusConfiguration args = configuration.getChild("compilerArgs", false);
        if (args == null) {
            args = configuration.getChild("compilerArguments", false);
        }
        if (args == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (PlexusConfiguration child : args.getChildren()) {
            String value = child.getValue(null);
            if (isNotBlank(value)) {
                result.add(trim(value));
            } else if (child.getName() != null && child.getName().startsWith("-")) {
                result.add(child.getName());
            }
        }
        return result;
    }
}
