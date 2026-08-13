package io.github.thunkware.auto.valhalla;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Java agent entry point. Installs {@link ValueClassTransformer} to convert
 * eligible classes into JEP 401 value objects at load time.
 *
 * <h2>Activating the agent</h2>
 * <pre>{@code
 * java --enable-preview -javaagent:auto-valhalla.jar[=options] -cp ... Main
 * }</pre>
 *
 * <h2>Options</h2>
 * Flags may be supplied as agent arguments, system properties, or environment
 * variables (in that order of precedence). The canonical, hyphenated form is
 * used everywhere; a {@code .config} file may also supply options. Within the
 * agent-argument list, later options override earlier ones, and a {@code
 * .config} file is expanded in place — so if it appears first, later CLI
 * options override it, and vice versa.
 * <ul>
 *   <li>{@code auto-valhalla.includes} — comma-separated classes or packages to
 *       convert. A value ending in {@code .} matches a package prefix
 *       ({@code startsWith}); otherwise it is an exact class name.</li>
 *   <li>{@code auto-valhalla.excludes} — same matching rules, but excludes the
 *       matching classes (takes precedence over includes and the annotation).</li>
 *   <li>{@code auto-valhalla.includes-file} / {@code auto-valhalla.excludes-file}
 *       — path to a file with one pattern per line (blank lines and {@code #}
 *       comments ignored).</li>
     *   <li>{@code auto-valhalla.annotation-mode} — modes narrowing classes
     *       selected by {@code @AutoValhalla} (default {@code safe}).</li>
     *   <li>{@code auto-valhalla.includes-mode} — modes narrowing classes
     *       selected by {@code includes} (default {@code yolo} =
     *       {@code mark-class-final,ignore-synchronized,mark-fields-final}).</li>
 *   <li>{@code auto-valhalla.debug=true} — verbose logging of decisions.</li>
 *   <li>{@code auto-valhalla.annotation.on-fail-throw=true} (default) — surface
 *       a loud {@link java.lang.LinkageError} if an <em>annotation-selected</em>
 *       class cannot be safely transformed instead of leaving it an identity
 *       class.</li>
 *   <li>{@code auto-valhalla.includes.on-fail-throw=true} (default false) — the
 *       same, for <em>includes-selected</em> classes (off by default so a broad
 *       includes sweep cannot crash the application).</li>
 *   <li>{@code auto-valhalla.annotation.on-fail-append-to=file} /
 *       {@code auto-valhalla.includes.on-fail-append-to=file} — append the
 *       Java dot name of each failing class (e.g. {@code com.example.Foo},
 *       not {@code com/example/Foo}) to the given file, per selection
 *       source (created if necessary). Dot names here read naturally for
 *       {@code auto-valhalla.includes-file} / {@code auto-valhalla.excludes-file}
 *       feedback.</li>
 *   <li>{@code auto-valhalla.annotation.on-success-append-to=file} /
 *       {@code auto-valhalla.includes.on-success-append-to=file} — append the
 *       Java dot name of each class that is successfully converted to a value
 *       class. The file is read at start-up so a name already present is not
 *       re-appended; a missing file is treated as empty, never an error.</li>
 *   <li>{@code auto-valhalla.config=file} — read options from a Java properties
 *       file (keys may omit the {@code auto-valhalla.} prefix).</li>
 * </ul>
 * A class selected by both the annotation and {@code includes} follows the
 * annotation settings for failure handling (an explicit in-source opt-in is
 * the stronger statement). Every class annotated with {@code @AutoValhalla} is
 * always converted.
 *
 * <p>The same options may also be set as environment variables using the
 * {@code AUTO_VALHALLA_*} convention: the canonical key (without the
 * {@code auto-valhalla.} prefix) is upper-cased and has its dashes turned into
 * underscores, e.g. {@code auto-valhalla.includes} becomes
 * {@code AUTO_VALHALLA_INCLUDES}. Environment variables have the lowest
 * precedence (agent arguments beat system properties, which beat environment
 * variables).
 *
 * <p>The agent jar must be built for and run on a JDK 28 (or later) with
 * {@code --enable-preview}, since the transformed class files use preview
 * class-file versions and the value-object semantics are preview features.
 */
public final class AutoValhallaAgent {

    private AutoValhallaAgent() {}

    /**
     * Whether the running JVM has Project Valhalla / value classes available.
     * Determined once from the JVM's input arguments: value classes are a preview
     * feature, so the agent can only run when the JVM was started with
     * {@code --enable-preview}. If it was not, the agent disables itself
     * gracefully (otherwise it would hand the JVM preview class files it refuses
     * to accept).
     */
    private static final boolean VALHALLA_AVAILABLE = valhallaAvailable();

    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst, false);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst, true);
    }

    private static void install(String agentArgs, Instrumentation inst, boolean attach) {
        if (!VALHALLA_AVAILABLE) {
            System.err.println("[auto-valhalla] WARNING: Project Valhalla / value classes "
                    + "are not available in this JVM (pass --enable-preview on JDK 28+). "
                    + "The agent is disabled and classes are left as identity classes.");
            return;
        }
        Config cfg = parse(agentArgs);
        ValueClassTransformer transformer = new ValueClassTransformer(
                cfg.includes(), cfg.excludes(),
                cfg.annotationMode(), cfg.includesMode(),
                cfg.debug(),
                cfg.annotationOnFailThrow(), cfg.annotationOnFailAppendTo(),
                cfg.annotationOnSuccessAppendTo(),
                cfg.includesOnFailThrow(), cfg.includesOnFailAppendTo(),
                cfg.includesOnSuccessAppendTo());
        // canRetransform = true so dynamically attached classes can be fixed up too
        inst.addTransformer(transformer, true);

        if (cfg.debug()) {
            System.err.println("[auto-valhalla] attached"
                    + (attach ? " (dynamically)" : "")
                    + "; includes=" + cfg.includes()
                    + " excludes=" + cfg.excludes()
                    + " annotation-mode=" + cfg.annotationMode()
                    + " includes-mode=" + cfg.includesMode()
                    + " annotation.on-fail-throw=" + cfg.annotationOnFailThrow()
                    + " includes.on-fail-throw=" + cfg.includesOnFailThrow());
        }
    }

    static Config parse(String agentArgs) {
        // Ordered list of [canonicalKey, value] assignments. Later entries win
        // for scalar options; include/exclude sets are replaced wholesale.
        List<String[]> assigns = new ArrayList<>();

        // System properties, then environment variables (lowest precedence).
        for (String key : Config.KNOWN) {
            String full = "auto-valhalla." + key;
            String v = System.getProperty(full);
            if (v == null) {
                v = System.getenv(envName(full));
            }
            if (v != null) {
                emit(assigns, key, v);
            }
        }

        // Agent arguments (highest precedence), with .config expanded in place.
        // Split only on top-level commas; values (e.g. includes=A,B) may contain
        // commas, so a token is treated as the start of a new assignment only
        // when it looks like "key=" for a known option.
        if (agentArgs != null) {
            for (String tok : splitAgentArgs(agentArgs)) {
                if (tok.isBlank()) {
                    continue;
                }
                tok = tok.trim();
                int eq = tok.indexOf('=');
                if (eq >= 0) {
                    String key = tok.substring(0, eq).trim();
                    String value = tok.substring(eq + 1).trim();
                    String canonical = canonicalKey(key);
                    if (canonical != null) {
                        emit(assigns, canonical, value);
                    }
                }
                // bare tokens are ignored
            }
        }

        Set<String> includes = new HashSet<>();
        Set<String> excludes = new HashSet<>();
        Set<Mode> annotationMode = EnumSet.copyOf(Mode.ANNOTATION_DEFAULT);
        Set<Mode> includesMode = EnumSet.copyOf(Mode.INCLUDES_DEFAULT);
        boolean debug = false;
        // annotation-selected classes are an explicit opt-in: fail loudly by
        // default. includes sweep broadly: stay quiet by default.
        boolean annotationOnFailThrow = true;
        String annotationOnFailAppendTo = null;
        String annotationOnSuccessAppendTo = null;
        boolean includesOnFailThrow = false;
        String includesOnFailAppendTo = null;
        String includesOnSuccessAppendTo = null;

        for (String[] a : assigns) {
            switch (a[0]) {
                case Config.INCLUDES -> includes.addAll(parsePatternSet(a[1]));
                case Config.EXCLUDES -> excludes.addAll(parsePatternSet(a[1]));
                case Config.INCLUDES_FILE -> includes.addAll(readPatternFile(a[1]));
                case Config.EXCLUDES_FILE -> excludes.addAll(readPatternFile(a[1]));
                case Config.ANNOTATION_MODE -> annotationMode = Mode.parse(a[1], Mode.ANNOTATION_DEFAULT);
                case Config.INCLUDES_MODE -> includesMode = Mode.parse(a[1], Mode.INCLUDES_DEFAULT);
                case Config.DEBUG -> debug = Boolean.parseBoolean(a[1]);
                case Config.ANNOTATION_ON_FAIL_THROW ->
                        annotationOnFailThrow = Boolean.parseBoolean(a[1]);
                case Config.INCLUDES_ON_FAIL_THROW ->
                        includesOnFailThrow = Boolean.parseBoolean(a[1]);
                case Config.ANNOTATION_ON_FAIL_APPEND_TO -> {
                    String t = a[1].trim();
                    annotationOnFailAppendTo = t.isEmpty() ? null : t;
                }
                case Config.ANNOTATION_ON_SUCCESS_APPEND_TO -> {
                    String t = a[1].trim();
                    annotationOnSuccessAppendTo = t.isEmpty() ? null : t;
                }
                case Config.INCLUDES_ON_FAIL_APPEND_TO -> {
                    String t = a[1].trim();
                    includesOnFailAppendTo = t.isEmpty() ? null : t;
                }
                case Config.INCLUDES_ON_SUCCESS_APPEND_TO -> {
                    String t = a[1].trim();
                    includesOnSuccessAppendTo = t.isEmpty() ? null : t;
                }
                default -> { /* unreachable */ }
            }
        }
        return new Config(includes, excludes, annotationMode, includesMode,
                debug, annotationOnFailThrow, annotationOnFailAppendTo,
                annotationOnSuccessAppendTo,
                includesOnFailThrow, includesOnFailAppendTo,
                includesOnSuccessAppendTo);
    }

    private static void emit(List<String[]> assigns, String key, String value) {
        if (key.equals(Config.CONFIG)) {
            // expand the referenced properties file in place
            Properties props = new Properties();
            try (BufferedReader br = Files.newBufferedReader(Path.of(value.trim()))) {
                props.load(br);
            } catch (IOException e) {
                System.err.println("[auto-valhalla] cannot read config file "
                        + value + ": " + e);
                return;
            }
            for (String rawKey : props.stringPropertyNames()) {
                String canonical = canonicalKey(rawKey);
                if (canonical != null && !canonical.equals(Config.CONFIG)) {
                    emit(assigns, canonical, props.getProperty(rawKey));
                }
            }
            return;
        }
        assigns.add(new String[] { key, value });
    }

    private static String canonicalKey(String input) {
        String s = input.trim();
        if (s.startsWith("auto-valhalla.")) {
            s = s.substring("auto-valhalla.".length());
        }
        return Config.KNOWN.contains(s) ? s : null;
    }

    /**
     * Normalizes a user-supplied pattern to an internal form:
     * {@code *} matches everything; {@code foo.*} or {@code foo/*} becomes a
     * package prefix (trailing slash); a value ending in {@code .} becomes a
     * package prefix; a value containing a dot is an exact class name; a bare
     * word (no dot) is treated as a package prefix.
     */
    static String normalizePattern(String p) {
        String t = p.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.equals("*")) {
            return "*";
        }
        if (t.endsWith(".*") || t.endsWith("/*")) {
            return t.substring(0, t.length() - 2).replace('.', '/') + "/";
        }
        if (t.endsWith(".")) {
            return t.substring(0, t.length() - 1).replace('.', '/') + "/";
        }
        if (t.indexOf('.') >= 0) {
            return t.replace('.', '/');
        }
        return t.replace('.', '/') + "/";
    }

    private static Set<String> parsePatternSet(String value) {
        Set<String> set = new HashSet<>();
        for (String part : value.split("[;,]")) {
            String n = normalizePattern(part);
            if (n != null) {
                set.add(n);
            }
        }
        return set;
    }

    private static Set<String> readPatternFile(String path) {
        Set<String> set = new HashSet<>();
        try (BufferedReader br = Files.newBufferedReader(Path.of(path.trim()))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                String n = normalizePattern(t);
                if (n != null) {
                    set.add(n);
                }
            }
        } catch (IOException e) {
            System.err.println("[auto-valhalla] cannot read pattern file "
                    + path + ": " + e);
        }
        return set;
    }

    static List<String> splitAgentArgs(String agentArgs) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : agentArgs.split(",")) {
            if (current.isEmpty()) {
                current.append(part);
            } else if (isTopLevelAssignment(part)) {
                result.add(current.toString());
                current.setLength(0);
                current.append(part);
            } else {
                current.append(',').append(part);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    static boolean isTopLevelAssignment(String part) {
        String t = part.trim();
        int eq = t.indexOf('=');
        if (eq <= 0) {
            return false;
        }
        return canonicalKey(t.substring(0, eq).trim()) != null;
    }

    private static String envName(String prop) {
        String body;
        if (prop.startsWith("auto-valhalla.")) {
            body = "AUTO_VALHALLA_" + prop.substring("auto-valhalla.".length()).replace('-', '_');
        } else if (prop.startsWith("autovalhalla.")) {
            body = "AUTO_VALHALLA_" + prop.substring("autovalhalla.".length()).replace('-', '_');
        } else {
            body = prop.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && body.charAt(i - 1) != '_') {
                char prev = body.charAt(i - 1);
                if (Character.isLowerCase(prev) || Character.isDigit(prev)) {
                    sb.append('_');
                }
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /**
     * Returns {@code true} if the JVM was started with {@code --enable-preview},
     * which is required for value classes (a preview feature) to be usable.
     */
    private static boolean valhallaAvailable() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().contains("--enable-preview");
    }
}
