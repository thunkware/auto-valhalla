package io.github.thunkware.auto.valhalla.maven.support;

import static io.github.thunkware.auto.valhalla.maven.support.Utils.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.support.Utils.trim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.codehaus.plexus.configuration.PlexusConfiguration;

/** Evaluates nested compiler configuration before inherited plugin configuration. */
public final class ConfigEvaluator {

    public enum Origin {
        nestedFirst,
        projectFirst,
        nestedOnly,
        projectOnly
    }

    private final PlexusConfiguration[] configurations;

    private ConfigEvaluator(PlexusConfiguration nested, PlexusConfiguration inherited, Origin origin) {
        this.configurations = configurations(origin, nested, inherited);
    }

    public static ConfigEvaluator of(PlexusConfiguration nested, PlexusConfiguration inherited) {
        return of(nested, inherited, Origin.nestedFirst);
    }

    public static ConfigEvaluator of(
            PlexusConfiguration nested, PlexusConfiguration inherited, Origin origin) {
        return new ConfigEvaluator(nested, inherited, origin);
    }

    public static ConfigEvaluator of(
            PlexusConfiguration nested, PlexusConfiguration inherited, String origin) {
        return of(nested, inherited, parseOrigin(origin));
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
            Origin origin, PlexusConfiguration nested, PlexusConfiguration inherited) {
        switch (origin) {
            case projectFirst:
                return new PlexusConfiguration[] {inherited, nested};
            case nestedOnly:
                return new PlexusConfiguration[] {nested};
            case projectOnly:
                return new PlexusConfiguration[] {inherited};
            case nestedFirst:
            default:
                return new PlexusConfiguration[] {nested, inherited};
        }
    }

    private static Origin parseOrigin(String origin) {
        if (origin == null || origin.trim().isEmpty()) {
            return Origin.nestedFirst;
        }
        for (Origin value : Origin.values()) {
            if (value.name().equals(origin)) {
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
        PlexusConfiguration child = configuration.getChild(name);
        String value = child == null ? null : child.getValue(null);
        return isNotBlank(value) ? trim(value) : null;
    }

    private static List<String> compilerArgs(PlexusConfiguration configuration) {
        if (configuration == null) {
            return Collections.emptyList();
        }
        PlexusConfiguration args = configuration.getChild("compilerArgs");
        if (args == null) {
            args = configuration.getChild("compilerArguments");
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
