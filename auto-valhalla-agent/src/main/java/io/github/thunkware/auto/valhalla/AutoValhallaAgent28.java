package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.logger.ApplicationLoggerBridgeTransformer;
import io.github.thunkware.auto.valhalla.logger.ApplicationLoggerFlags;
import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import io.github.thunkware.auto.valhalla.logger.InternalLoggerFactory;
import io.github.thunkware.auto.valhalla.logger.LoggingSystem;
import io.github.thunkware.auto.valhalla.util.Failable;
import io.github.thunkware.auto.valhalla.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
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
 * System properties take precedence over environment variables, and both take
 * precedence over the {@code auto-valhalla.config} file. The canonical,
 * hyphenated form is used everywhere.
 * <ul>
 *   <li>{@code auto-valhalla.includes} — comma-separated classes or packages to
 *       convert. A pattern {@code X} matches a class named {@code X}, or a
 *       package named {@code X} or starting with {@code X.} (recursive);
 *       no trailing dot is required.</li>
 *   <li>{@code auto-valhalla.excludes} — same matching rules, but excludes the
 *       matching classes (takes precedence over includes and the annotation).</li>
 *   <li>{@code auto-valhalla.includes-files} / {@code auto-valhalla.excludes-files}
 *       — path to a file with one pattern per line (blank lines and {@code #}
 *       comments ignored). Multiple files may be separated by {@code ;} or
 *       {@code ,}; each option may also be repeated and all files are merged.</li>
 *   <li>{@code auto-valhalla.annotation-mode} — modes narrowing classes
 *       selected by {@code @AutoValhalla} (default {@code safe}).</li>
 *   <li>{@code auto-valhalla.includes-mode} — modes narrowing classes
 *       selected by {@code includes} (default {@code safe}; {@code yolo} is the
 *       shorthand for
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
 *       file. Keys may be written with or without the {@code auto-valhalla.}
 *       prefix, {@code logging.level.<name>} entries are honoured, and unknown
 *       keys are logged as warnings.</li>
 * </ul>
 * A class selected by both the annotation and {@code includes} follows the
 * annotation settings for failure handling (an explicit in-source opt-in is
 * the stronger statement).
 *
 * <p>The same options may also be set as environment variables using the
 * {@code AUTO_VALHALLA_*} convention: the canonical key (without the
 * {@code auto-valhalla.} prefix) is upper-cased and has its dashes turned into
 * underscores, e.g. {@code auto-valhalla.includes} becomes
 * {@code AUTO_VALHALLA_INCLUDES}. Precedence, highest first: system properties,
 * environment variables, then the {@code auto-valhalla.config} file. The
 * {@code premain}/{@code agentmain} argument string is not used for options.
 * Per-logger levels ({@code logging.level.<name>}) are read from system
 * properties and the config file only, not from the environment.
 *
 * <p>The agent jar must be built for and run on a JDK 28 (or later) with
 * {@code --enable-preview}, since the transformed class files use preview
 * class-file versions and the value-object semantics are preview features.
 */
// public class with non-public members (other than the entry points) to ensure
// javadoc visibility
public final class AutoValhallaAgent28 {

    private static final InternalLogger LOG = InternalLoggerFactory.getLogger(AutoValhallaAgent28.class);
    public static final String INSTALL_ATTEMPTED = AutoValhallaAgent28.class.getSimpleName() + ".installAttempted";

    private AutoValhallaAgent28() {
    }

    /** Public so the JDK 5 shim can call it even when the two classes end up in
     *  different runtime packages, which happens when the agent jar is appended to
     *  the bootstrap class loader search. */
    public static boolean installAttempted() {
        return Boolean.getBoolean(INSTALL_ATTEMPTED);
    }

    /** Public for the same reason as {@link #installAttempted()}. */
    public static void install(Instrumentation inst) {
        if (installAttempted()) {
            return;
        }
        // get-around classloader problems in case -javaagent is mixed with attach mechanism in uber jars
        System.setProperty(INSTALL_ATTEMPTED, "true");

        Config cfg = parse();
        initLogging(inst, cfg);
        Stats.accept(cfg);

        ValueClassTransformer transformer = new ValueClassTransformer(cfg);
        // canRetransform = true so dynamically attached classes can be fixed up too
        inst.addTransformer(transformer, true);
    }

    private static void initLogging(Instrumentation inst, Config cfg) {
        InternalLoggerFactory.setLevel(cfg.loggerLevels.remove("root"));
        cfg.loggerLevels.forEach(InternalLoggerFactory::setLevel);
        InternalLoggerFactory.setSystem(cfg.logging);
        if (LoggingSystem.findOrNull(cfg.logging) == LoggingSystem.APPLICATION) {
            ApplicationLoggerFlags.enableApplicationLoggingSystem();
            inst.addTransformer(new ApplicationLoggerBridgeTransformer(), false);
        }
        String version = getVersion();
        LOG.info(version.isEmpty() ? "Starting agent" : "Starting agent " + version);
    }

    private static String getVersion() {
        URL url = AutoValhallaAgent28.class.getResource("/git-auto-valhalla-agent.properties");
        Properties properties = new Properties();
        return loadProperties(url, properties).getProperty("git.build.version", "");
    }

    private static Properties loadProperties(URL url, Properties properties) {
        Failable.runQuietly(() -> {
            try (InputStream stream = url.openStream()) {
                final byte[] bytes = stream.readAllBytes();
                properties.load(new ByteArrayInputStream(bytes));
            }
        });
        return properties;
    }

    static Config parse() {
        // Ordered list of [canonicalKey, value] assignments. Later entries win
        // for scalar options; include/exclude sets accumulate across sources.
        List<String[]> assigns = new ArrayList<>();

        // Lowest precedence first: the config file, so that a system property or
        // environment variable set alongside it overrides the file.
        String configFile = propertyOrEnv("auto-valhalla." + Config.CONFIG);
        if (configFile != null) {
            emitConfigFile(assigns, configFile);
        }

        // System properties take precedence over environment variables.
        for (String key : Config.KNOWN) {
            if (key.equals(Config.CONFIG)) {
                continue;
            }
            String v = propertyOrEnv("auto-valhalla." + key);
            if (v != null) {
                assigns.add(new String[]{key, v});
            }
        }

        // Per-logger level overrides: -Dlogging.level.<name>=<level>
        // These are not in Config.KNOWN (the suffix is a logger name, not a fixed key).
        final Properties properties = System.getProperties();
        for (String prop : properties.stringPropertyNames()) {
            if (prop.startsWith(Config.LOG_LEVEL_PREFIX)) {
                String loggerName = StringUtils.substringAfter(prop, Config.LOG_LEVEL_PREFIX);
                if (!loggerName.isEmpty()) {
                    String v = properties.getProperty(prop);
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

    /** System property, or the corresponding environment variable when no system
     *  property is set. */
    private static String propertyOrEnv(String fullKey) {
        String v = System.getProperty(fullKey);
        return v != null ? v : System.getenv(envName(fullKey));
    }

    /**
     * Expands the properties file named by {@code auto-valhalla.config} into
     * {@code assigns}. Keys may be written in canonical form ({@code includes})
     * or with the {@code auto-valhalla.} prefix ({@code auto-valhalla.includes});
     * {@code logging.level.<logger>} entries are honoured too. A nested
     * {@code config} key is ignored, and an unrecognized key is reported rather
     * than silently dropped.
     */
    private static void emitConfigFile(List<String[]> assigns, String value) {
        Properties props = new Properties();
        Path file = Path.of(value.trim());
        Failable.run(() -> loadProperties(file.toUri().toURL(), props),
                     t -> LOG.error("cannot read config file " + value, t));
        for (String rawKey : props.stringPropertyNames()) {
            String key = rawKey.trim();
            if (key.startsWith("auto-valhalla.")) {
                key = StringUtils.substringAfter(key, "auto-valhalla.");
            }
            if (key.equals(Config.CONFIG)) {
                LOG.warning("Nested " + Config.CONFIG + " key ignored in config file " + value);
                continue;
            }
            if (!Config.KNOWN.contains(key) && !key.startsWith(Config.LOG_LEVEL_PREFIX)) {
                LOG.warning("Unknown key ignored in config file " + value + ": " + rawKey);
                continue;
            }
            assigns.add(new String[]{key, props.getProperty(rawKey)});
        }
    }

    /**
     * Normalizes a user-supplied pattern to an internal (slash) form.
     * Matching is uniform: a pattern {@code X} matches a class whose name is
     * {@code X}, or whose package name is {@code X} or starts with {@code X.}
     * (recursive package match). No trailing dot is required. {@code *}
     * matches everything. {@code foo.*} or {@code foo/*} (or a trailing
     * {@code .}) is an explicit recursive package prefix.
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
        return t.replace('.', '/');
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

        List<String> lines;
        try {
            Path file = Path.of(path.trim());
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            if (!quiet) {
                LOG.error("cannot read pattern file " + path + ": " + e);
            }
            return set;
        }
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String n = normalizePattern(t);
            if (n != null) {
                set.add(n);
            }
        }
        return set;
    }

    /** Returns {@code " label=value"}, or {@code ""} when value is null or an
     *  empty collection. Collections are formatted as {@code a,b,c} (no brackets). */
    @SuppressWarnings("rawtypes")
    private static String getLogString(String label, Object value) {
        //noinspection IfCanBeSwitch
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
}
