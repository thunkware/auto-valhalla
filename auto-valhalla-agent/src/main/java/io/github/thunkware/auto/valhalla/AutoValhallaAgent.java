package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.logger.ApplicationLoggerBridgeTransformer;
import io.github.thunkware.auto.valhalla.logger.ApplicationLoggerFlags;
import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import io.github.thunkware.auto.valhalla.logger.InternalLoggerFactory;
import io.github.thunkware.auto.valhalla.logger.LoggingMode;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Java agent entry point. Installs {@link ValueClassTransformer} to convert
 * eligible classes into JEP 401 value objects at load time.
 *
 * <h2>Activating the agent</h2>
 * <pre>{@code
 * java --enable-preview -javaagent:auto-valhalla.jar -cp ... Main
 * }</pre>
 *
 * <h2>Options</h2>
 * Flags may be supplied as system properties or environment variables.
 * System properties take precedence over environment variables. The
 * canonical, hyphenated form is used everywhere; a {@code .config} file
 * may also supply options.
 * <ul>
 *   <li>{@code auto-valhalla.includes} — comma-separated classes or packages to
 *       convert. A value ending in {@code .} matches a package prefix
 *       ({@code startsWith}); otherwise it is an exact class name.</li>
 *   <li>{@code auto-valhalla.excludes} — same matching rules, but excludes the
 *       matching classes (takes precedence over includes and the annotation).</li>
 *   <li>{@code auto-valhalla.includes-files} / {@code auto-valhalla.excludes-files}
 *       — path to a file with one pattern per line (blank lines and {@code #}
 *       comments ignored). Multiple files may be separated by {@code ;} or
 *       {@code ,}; each option may also be repeated and all files are merged.</li>
     *   <li>{@code auto-valhalla.annotation-mode} — modes narrowing classes
     *       selected by {@code @AutoValhalla} (default {@code safe}).</li>
     *   <li>{@code auto-valhalla.includes-mode} — modes narrowing classes
     *       selected by {@code includes} (default {@code yolo} =
     *       {@code mark-class-final,remove-synchronized,mark-fields-final}).</li>
 *   <li>{@code logging.level.root} — root logging level: {@code off}, {@code error},
 *       {@code warning}, {@code info} (default), {@code debug}. Controls verbosity
 *       of messages to stderr. Use {@code logging.level.<name>} for per-logger overrides.</li>
 *   <li>{@code auto-valhalla.annotation.on-fail-append-to=file} /
 *       {@code auto-valhalla.includes.on-fail-append-to=file} — append the
 *       class name of each failing class (e.g. {@code com.example.Foo},
 *       not {@code com/example/Foo}) to the given file, per selection
 *       source (created if necessary). Dot names here read naturally for
 *       {@code auto-valhalla.includes-files} / {@code auto-valhalla.excludes-files}
 *       feedback.</li>
 *   <li>{@code auto-valhalla.annotation.on-success-append-to=file} /
 *       {@code auto-valhalla.includes.on-success-append-to=file} — append the
 *       class name of each class that is successfully converted to a value
 *       class. The file is read at start-up so a name already present is not
 *       re-appended; a missing file is treated as empty, never an error.</li>
 *   <li>{@code auto-valhalla.synchronization-monitor.append-to=file} — when
 *       {@code Mode.SYNCHRONIZATION_MONITOR} is enabled, append the class name
 *       of each class being synchronized on at runtime. Useful for detecting value
 *       classes that are locked, which causes {@link java.lang.IdentityException}.
 *       The file is read at start-up so names already present are not re-appended.
 *       Default: {@code auto-valhalla.synchronization.txt}.</li>
 *   <li>Excludes default: when no {@code excludes} or {@code excludes-files} is
 *       supplied, patterns from {@code auto-valhalla.failures.txt} and
 *       {@code auto-valhalla.synchronization.txt} are automatically excluded,
 *       allowing logged failures and problematic classes to be skipped in future
 *       runs. Supplying any explicit excludes disables these defaults entirely.</li>
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

    private static final InternalLogger LOG = InternalLoggerFactory.getLogger(AutoValhallaAgent.class);

    /**
     * Whether the running JVM has Project Valhalla / value classes available.
     * Determined once from the JVM's input arguments: value classes are a preview
     * feature, so the agent can only run when the JVM was started with
     * {@code --enable-preview}. If it was not, the agent disables itself
     * gracefully (otherwise it would hand the JVM preview class files it refuses
     * to accept).
     */
    private static final boolean VALHALLA_AVAILABLE = valhallaAvailable();

    private AutoValhallaAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        if (agentArgs != null && !agentArgs.isBlank()) {
            LOG.warning("Agent arguments are no longer supported. "
                    + "Use system properties (-Dauto-valhalla.key=value) or environment "
                    + "variables instead. Ignoring: " + agentArgs);
        }
        install(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        if (agentArgs != null && !agentArgs.isBlank()) {
            LOG.warning("Agent arguments are no longer supported. "
                    + "Use system properties (-Dauto-valhalla.key=value) or environment "
                    + "variables instead. Ignoring: " + agentArgs);
        }
        install(inst);
    }

    private static void install(Instrumentation inst) {
        if (!VALHALLA_AVAILABLE) {
            LOG.warning("Project Valhalla / value classes "
                    + "are not available in this JVM (pass --enable-preview on JDK 28+). "
                    + "The agent is disabled and classes are left as identity classes.");
            return;
        }
        Config cfg = parse();
        initLogging(inst, cfg);
        ValueClassTransformer transformer = new ValueClassTransformer(cfg);
        // canRetransform = true so dynamically attached classes can be fixed up too
        inst.addTransformer(transformer, true);
    }

    private static void initLogging(Instrumentation inst, Config cfg) {
        InternalLoggerFactory.setLevel(cfg.loggerLevels.remove("root"));
        cfg.loggerLevels.forEach(InternalLoggerFactory::setLevel);
        InternalLoggerFactory.setMode(cfg.logging);
        if (LoggingMode.findOrNull(cfg.logging) == LoggingMode.APPLICATION) {
            ApplicationLoggerFlags.enableApplicationMode();
            inst.addTransformer(new ApplicationLoggerBridgeTransformer(), false);
        }
        LOG.info("Starting agent");
    }

    static Config parse() {
        // Ordered list of [canonicalKey, value] assignments. Later entries win
        // for scalar options; include/exclude sets are replaced wholesale.
        List<String[]> assigns = new ArrayList<>();

        // System properties take precedence over environment variables.
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

        // Per-logger level overrides: -Dlogging.level.<name>=<level>
        // These are not in Config.KNOWN (the suffix is a logger name, not a fixed key).
        for (String prop : System.getProperties().stringPropertyNames()) {
            if (prop.startsWith(Config.LOG_LEVEL_PREFIX)) {
                String loggerName = StringUtils.substringAfter(prop, Config.LOG_LEVEL_PREFIX);
                if (!loggerName.isEmpty()) {
                    String v = System.getProperty(prop);
                    if (v != null) {
                        assigns.add(new String[]{Config.LOG_LEVEL_PREFIX + loggerName, v});
                    }
                }
            }
        }

        checkUnkownConfig();

        Config cfg = new Config();
        boolean userSuppliedExcludes = false;

        for (String[] a : assigns) {
            switch (a[0]) {
                case Config.INCLUDES -> cfg.includes.addAll(parsePatternSet(a[1]));
                case Config.EXCLUDES -> {
                    cfg.excludes.addAll(parsePatternSet(a[1]));
                    userSuppliedExcludes = true;
                }
                case Config.INCLUDES_FILES -> {
                    for (String p : a[1].split("[;,]")) {
                        String t = p.trim();
                        if (!t.isEmpty()) {
                            cfg.includesFiles.add(t);
                        }
                    }
                }
                case Config.EXCLUDES_FILES -> {
                    for (String p : a[1].split("[;,]")) {
                        String t = p.trim();
                        if (!t.isEmpty()) {
                            cfg.excludesFiles.add(t);
                        }
                    }
                    userSuppliedExcludes = true;
                }
                case Config.ANNOTATION_MODE -> cfg.annotationMode = Mode.parse(a[1], Mode.ANNOTATION_DEFAULT);
                case Config.INCLUDES_MODE -> cfg.includesMode = Mode.parse(a[1], Mode.INCLUDES_DEFAULT);
                case Config.ANNOTATION_ON_FAIL_APPEND_TO -> {
                    String t = a[1].trim();
                    cfg.annotationOnFailAppendTo = t.isEmpty() ? null : t;
                }
                case Config.ANNOTATION_ON_SUCCESS_APPEND_TO -> {
                    String t = a[1].trim();
                    cfg.annotationOnSuccessAppendTo = t.isEmpty() ? null : t;
                }
                case Config.INCLUDES_ON_FAIL_APPEND_TO -> {
                    String t = a[1].trim();
                    cfg.includesOnFailAppendTo = t.isEmpty() ? null : t;
                }
                case Config.INCLUDES_ON_SUCCESS_APPEND_TO -> {
                    String t = a[1].trim();
                    cfg.includesOnSuccessAppendTo = t.isEmpty() ? null : t;
                }
                case Config.SYNCHRONIZATION_MONITOR_APPEND_TO -> {
                    String t = a[1].trim();
                    cfg.synchronizationMonitorAppendTo = t.isEmpty() ? null : t;
                }
                case Config.LOGGING -> cfg.logging = a[1].trim();
                default -> {
                    if (a[0].startsWith(Config.LOG_LEVEL_PREFIX)) {
                        String loggerName = StringUtils.substringAfter(a[0], Config.LOG_LEVEL_PREFIX);
                        loggerName = loggerName.equalsIgnoreCase("root") ? "root" : loggerName;
                        cfg.loggerLevels.put(loggerName, a[1].trim());
                    }
                }
            }
        }

        // Log the configuration as provided (before resolving file contents);
        logConfig(cfg);

        resolveFiles(cfg, userSuppliedExcludes);

        return cfg;
    }

    private static void logConfig(Config cfg) {
        // null and empty values are omitted.
        LOG.info("Configuration:"
                + getLogString(Config.INCLUDES, cfg.includes)
                + getLogString(Config.INCLUDES_FILES, cfg.includesFiles)
                + getLogString(Config.EXCLUDES, cfg.excludes)
                + getLogString(Config.EXCLUDES_FILES, cfg.excludesFiles)
                + getLogString(Config.ANNOTATION_MODE, cfg.annotationMode)
                + getLogString(Config.INCLUDES_MODE, cfg.includesMode)
                + getLogString(Config.ANNOTATION_ON_FAIL_APPEND_TO, cfg.annotationOnFailAppendTo)
                + getLogString(Config.ANNOTATION_ON_SUCCESS_APPEND_TO, cfg.annotationOnSuccessAppendTo)
                + getLogString(Config.INCLUDES_ON_FAIL_APPEND_TO, cfg.includesOnFailAppendTo)
                + getLogString(Config.INCLUDES_ON_SUCCESS_APPEND_TO, cfg.includesOnSuccessAppendTo)
                + getLogString(Config.SYNCHRONIZATION_MONITOR_APPEND_TO, cfg.synchronizationMonitorAppendTo)
                + getLogString("logging.level", cfg.loggerLevels)
                + getLogString(Config.LOGGING, cfg.logging));
    }

    private static void resolveFiles(Config cfg, boolean userSuppliedExcludes) {
        // Now resolve file contents
        for (String p : cfg.includesFiles) {
            cfg.includes.addAll(readPatternFile(p, false));
        }
        for (String p : cfg.excludesFiles) {
            cfg.excludes.addAll(readPatternFile(p, false));
        }

        // Default excludes: only when the user has not supplied any excludes.
        if (!userSuppliedExcludes) {
            cfg.excludes.addAll(readPatternFile("auto-valhalla.failures.txt", true));
            cfg.excludes.addAll(readPatternFile("auto-valhalla.synchronization.txt", true));
        }
    }

    private static void checkUnkownConfig() {
        for (String p : unknownSysProps(System.getProperties().stringPropertyNames())) {
            LOG.warning("Unknown system property ignored: " + p);
        }
        for (String e : unknownEnvVars(System.getenv().keySet())) {
            LOG.warning("Unknown environment variable ignored: " + e);
        }
    }

    private static void emit(List<String[]> assigns, String key, String value) {
        if (key.equals(Config.CONFIG)) {
            // expand the referenced properties file in place
            Properties props = new Properties();
            try (BufferedReader br = Files.newBufferedReader(Path.of(value.trim()))) {
                props.load(br);
            } catch (IOException e) {
                LOG.error("cannot read config file " + value + ": " + e);
                return;
            }
            for (String rawKey : props.stringPropertyNames()) {
                String s = rawKey.trim();
                if (s.startsWith("auto-valhalla.")) {
                    s = StringUtils.substringAfter(s, "auto-valhalla.");
                }
                boolean isKnown = Config.KNOWN.contains(s) && !s.equals(Config.CONFIG);
                boolean isLogLevelOverride = s.startsWith(Config.LOG_LEVEL_PREFIX);
                if (!isKnown && !isLogLevelOverride) {
                    continue;
                }
                emit(assigns, s, props.getProperty(rawKey));
            }
            return;
        }
        assigns.add(new String[] { key, value });
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
        if (t.endsWith(".*")) {
            return StringUtils.substringBeforeLast(t, ".*").replace('.', '/') + "/";
        }
        if (t.endsWith("/*")) {
            return StringUtils.substringBeforeLast(t, "/*").replace('.', '/') + "/";
        }
        if (t.endsWith(".")) {
            return StringUtils.substringBeforeLast(t, ".").replace('.', '/') + "/";
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

    private static Set<String> readPatternFile(String path, boolean quiet) {
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
            if (!quiet) {
                LOG.error("cannot read pattern file " + path + ": " + e);
            }
        }
        return set;
    }

    /** Returns {@code " label=value"}, or {@code ""} when value is null or an
     *  empty collection. Collections are formatted as {@code a,b,c} (no brackets). */
    @SuppressWarnings("rawtypes")
    private static String getLogString(String label, Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> c) {
            if (c.isEmpty()) {
                return "";
            }
            String combined = c.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
            return " " + label + "=" + combined;
        }
        if (value instanceof Enum) {
            value = ((Enum) value).name().toLowerCase(Locale.ENGLISH);
        }
        return " " + label + "=" + value;
    }

    /** Returns entries in {@code propNames} that start with {@code auto-valhalla.}
     *  but whose suffix is not a known option key. */
    static List<String> unknownSysProps(Set<String> propNames) {
        Set<String> known = new HashSet<>(Config.KNOWN);
        String prefix = "auto-valhalla.";
        List<String> result = new ArrayList<>();
        for (String prop : propNames) {
            if (prop.startsWith(prefix)) {
                String suffix = StringUtils.substringAfter(prop, prefix);
                if (!known.contains(suffix)) {
                    result.add(prop);
                }
            }
        }
        return result;
    }

    /** Returns entries in {@code envNames} that start with {@code AUTO_VALHALLA_}
     *  but do not correspond to any known option. */
    static List<String> unknownEnvVars(Set<String> envNames) {
        Set<String> knownEnv = new HashSet<>();
        for (String key : Config.KNOWN) {
            knownEnv.add(envName("auto-valhalla." + key));
        }
        List<String> result = new ArrayList<>();
        for (String env : envNames) {
            if (env.startsWith("AUTO_VALHALLA_") && !knownEnv.contains(env)) {
                result.add(env);
            }
        }
        return result;
    }

    private static String envName(String prop) {
        String body;
        if (prop.startsWith("auto-valhalla.")) {
            body = "AUTO_VALHALLA_" + StringUtils.substringAfter(prop, "auto-valhalla.").replace('-', '_').replace('.', '_');
        } else if (prop.startsWith("autovalhalla.")) {
            body = "AUTO_VALHALLA_" + StringUtils.substringAfter(prop, "autovalhalla.").replace('-', '_').replace('.', '_');
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
