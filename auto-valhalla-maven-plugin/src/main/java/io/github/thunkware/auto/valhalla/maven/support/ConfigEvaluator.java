package io.github.thunkware.auto.valhalla.maven.support;

import static io.github.thunkware.auto.valhalla.maven.support.Utils.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.support.Utils.trim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.codehaus.plexus.configuration.PlexusConfiguration;

/** Evaluates nested compiler configuration before inherited plugin configuration. */
public final class ConfigEvaluator {

    private final PlexusConfiguration nested;
    private final PlexusConfiguration inherited;

    public ConfigEvaluator(PlexusConfiguration nested, PlexusConfiguration inherited) {
        this.nested = nested;
        this.inherited = inherited;
    }

    public Boolean resolveBoolean(String name) {
        String value = resolveString(name);
        return value == null ? null : Boolean.valueOf(value);
    }

    public String resolveString(String name) {
        String value = value(nested, name);
        return value == null ? value(inherited, name) : value;
    }

    public List<String> resolveCompilerArgs() {
        List<String> args = compilerArgs(nested);
        return args.isEmpty() ? compilerArgs(inherited) : args;
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
