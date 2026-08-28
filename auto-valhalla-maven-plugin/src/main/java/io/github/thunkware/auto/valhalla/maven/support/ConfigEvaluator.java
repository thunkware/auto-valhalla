package io.github.thunkware.auto.valhalla.maven.support;

import static io.github.thunkware.auto.valhalla.maven.support.Utils.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.support.Utils.trim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.codehaus.plexus.configuration.PlexusConfiguration;

/** Evaluates nested compiler configuration and project plugin configuration. */
public final class ConfigEvaluator {

    private final PlexusConfiguration[] configurations;

    private ConfigEvaluator(PlexusConfiguration nested, PlexusConfiguration project, ConfigOrigin origin) {
        this.configurations = configurations(origin, nested, project);
    }

    private static String toUpperSnakeCase(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }

    public static ConfigEvaluator of(
            PlexusConfiguration nested, PlexusConfiguration project, ConfigOrigin origin) {
        return new ConfigEvaluator(nested, project, origin);
    }

    public static ConfigEvaluator of(
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
        throw new IllegalArgumentException("config-origin must be one of "
                + "nestedFirst, projectFirst, nestedOnly, projectOnly");
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
