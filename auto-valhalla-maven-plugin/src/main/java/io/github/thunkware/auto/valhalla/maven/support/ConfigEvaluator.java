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
        NESTED_FIRST,
        PROJECT_FIRST,
        NESTED_ONLY,
        PROJECT_ONLY
    }

    private final PlexusConfiguration nested;
    private final PlexusConfiguration inherited;
    private final Origin origin;

    public ConfigEvaluator(PlexusConfiguration nested, PlexusConfiguration inherited) {
        this(nested, inherited, "nestedFirst");
    }

    public ConfigEvaluator(PlexusConfiguration nested, PlexusConfiguration inherited, String origin) {
        this.nested = nested;
        this.inherited = inherited;
        this.origin = parseOrigin(origin);
    }

    public Boolean resolveBoolean(String name) {
        String value = resolveString(name);
        return value == null ? null : Boolean.valueOf(value);
    }

    public String resolveString(String name) {
        for (PlexusConfiguration configuration : configurations()) {
            String value = value(configuration, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public List<String> resolveCompilerArgs() {
        for (PlexusConfiguration configuration : configurations()) {
            List<String> args = compilerArgs(configuration);
            if (!args.isEmpty()) {
                return args;
            }
        }
        return Collections.emptyList();
    }

    private PlexusConfiguration[] configurations() {
        switch (origin) {
            case PROJECT_FIRST:
                return new PlexusConfiguration[] {inherited, nested};
            case NESTED_ONLY:
                return new PlexusConfiguration[] {nested};
            case PROJECT_ONLY:
                return new PlexusConfiguration[] {inherited};
            case NESTED_FIRST:
            default:
                return new PlexusConfiguration[] {nested, inherited};
        }
    }

    private static Origin parseOrigin(String origin) {
        if (origin == null || origin.trim().isEmpty()) {
            return Origin.NESTED_FIRST;
        }
        if ("nestedFirst".equals(origin)) {
            return Origin.NESTED_FIRST;
        }
        if ("projectFirst".equals(origin)) {
            return Origin.PROJECT_FIRST;
        }
        if ("nestedOnly".equals(origin)) {
            return Origin.NESTED_ONLY;
        }
        if ("projectOnly".equals(origin)) {
            return Origin.PROJECT_ONLY;
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
