package io.github.thunkware.auto.valhalla.maven.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Resolves the JDK compiler executable used by the Maven compiler plugin.
 */
public final class Javac {

    private Javac() {
    }

    /** Joins classpath entries with the platform path separator. */
    public static String joinClasspath(List<String> paths) {
        return String.join(File.pathSeparator, paths);
    }

    /** Lowest JDK feature version scanned for {@code java<N>.home} /
     *  {@code JAVA<N>_HOME}. */
    private static final int MIN_SCAN_VERSION = 28;

    /** Resolves the {@code javac} executable: an explicit {@code override}
     *  wins; otherwise every {@code java<N>.home} system property and
     *  {@code JAVA<N>_HOME} environment variable is checked for N >= 28, with
     *  the preferred version first and then increasing versions. The first
     *  usable home provides the compiler ({@code <home>/bin/javac}); otherwise
     *  the JDK running this JVM does. An exact
     *  {@code java<preferredVersion>.home}/{@code JAVA<preferredVersion>_HOME}
     *  is tried before the scan because its javac matches the target
     *  {@code --release} — {@code --enable-preview} only works there.
     */
    public static String resolveExecutable(String override, int preferredVersion) {
        return resolveExecutable(override, preferredVersion, System.getProperties(), System.getenv());
    }

    static String resolveExecutable(String override, int preferredVersion, Properties properties, Map<String, String> env) {
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }

        Map<Integer, List<Home>> homes = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            int version = numberedVersion(name, "java", ".home");
            if (version >= MIN_SCAN_VERSION) {
                addHome(homes, version, properties.getProperty(name),
                        "system property " + name);
            }
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            int version = numberedVersion(entry.getKey(), "JAVA", "_HOME");
            if (version >= MIN_SCAN_VERSION) {
                addHome(homes, version, entry.getValue(),
                        "environment variable " + entry.getKey());
            }
        }

        for (int version : versionsInPreferenceOrder(homes, preferredVersion)) {
            for (Home home : homes.get(version)) {
                File executable = new File(home.path.trim(), "bin/javac");
                if (!executable.isFile()) {
                    continue;
                }
                return executable.getAbsolutePath();
            }
        }

        return new File(System.getProperty("java.home", "java"),
                "bin/javac").getAbsolutePath();
    }

    private static int numberedVersion(String name, String prefix, String suffix) {
        if (!name.startsWith(prefix) || !name.endsWith(suffix)) {
            return -1;
        }
        String number = name.substring(prefix.length(), name.length() - suffix.length());
        if (number.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void addHome(Map<Integer, List<Home>> homes, int version,
            String path, String source) {
        if (path != null && !path.trim().isEmpty()) {
            homes.computeIfAbsent(version, ignored -> new ArrayList<>())
                    .add(new Home(path, source));
        }
    }

    private static List<Integer> versionsInPreferenceOrder(Map<Integer, List<Home>> homes,
            int preferredVersion) {
        List<Integer> versions = new ArrayList<>();
        if (homes.containsKey(preferredVersion)) {
            versions.add(preferredVersion);
        }
        for (int version : homes.keySet()) {
            if (version != preferredVersion) {
                versions.add(version);
            }
        }
        return versions;
    }

    private static final class Home {

        private final String path;
        private final String source;

        private Home(String path, String source) {
            this.path = path;
            this.source = source;
        }
    }

    static final class ProcessResult {

        public final int exit;
        public final String output;

        public ProcessResult(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }
}
