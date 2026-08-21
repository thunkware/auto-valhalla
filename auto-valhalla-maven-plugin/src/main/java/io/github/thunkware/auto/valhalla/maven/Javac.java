package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Runs {@code javac}; helpers shared by the selection pass
 * ({@link AnnotationProcessorRunner}) and the value-class compilation pass
 * ({@link AutoValhallaSourceTransformer}). Compilation either forks a
 * {@code javac} process or calls the in-process
 * {@link javax.tools.JavaCompiler} API.
 */
final class Javac {

    private Javac() {
        throw new AssertionError();
    }

    /** Joins classpath entries with the platform path separator. */
    static String joinClasspath(List<String> paths) {
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
     *
     *  @throws IllegalArgumentException when homes are configured but none of
     *          them contains a {@code bin/javac} */
    static String resolveExecutable(String override, int preferredVersion) {
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }

        Map<Integer, List<Home>> homes = new TreeMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            int version = numberedVersion(name, "java", ".home");
            if (version >= MIN_SCAN_VERSION) {
                addHome(homes, version, System.getProperty(name),
                        "system property " + name);
            }
        }
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            int version = numberedVersion(entry.getKey(), "JAVA", "_HOME");
            if (version >= MIN_SCAN_VERSION) {
                addHome(homes, version, entry.getValue(),
                        "environment variable " + entry.getKey());
            }
        }

        String invalidSource = null;
        String invalidPath = null;
        for (int version : versionsInPreferenceOrder(homes, preferredVersion)) {
            for (Home home : homes.get(version)) {
                File executable = new File(home.path.trim(), "bin/javac");
                if (!executable.isFile()) {
                    if (invalidSource == null) {
                        invalidSource = home.source;
                        invalidPath = executable.getAbsolutePath();
                    }
                    continue;
                }
                return executable.getAbsolutePath();
            }
        }

        if (invalidSource != null) {
            throw new IllegalArgumentException(invalidSource + " is set but there is "
                    + "no javac at " + invalidPath + "; no usable java<N>.home or "
                    + "JAVA<N>_HOME was found");
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

    /** Compiles {@code files} with the given options, either by forking the
     *  {@code javac} executable or (when {@code fork} is false) through the
     *  JDK running the JVM. The returned result carries the exit code and the
     *  merged compiler output. */
    static ProcessResult compile(boolean fork, String javacExecutable,
            List<String> options, List<File> files, String encoding)
            throws IOException {
        if (fork) {
            List<String> command = new ArrayList<>();
            command.add(javacExecutable);
            command.addAll(options);
            for (File file : files) {
                command.add(file.getAbsolutePath());
            }
            return run(command);
        }
        return compileInProcess(options, files, encoding);
    }

    private static ProcessResult compileInProcess(List<String> options,
            List<File> files, String encoding) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new ProcessResult(1, "no system java compiler available; "
                    + "fork=false requires Maven to run on a JDK");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Charset charset = resolveCharset(encoding);
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, charset)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(files);
            CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, units);
            boolean ok = task.call();
            return new ProcessResult(ok ? 0 : 1, format(diagnostics));
        } catch (IOException | RuntimeException e) {
            return new ProcessResult(1, format(diagnostics) + "\n" + e);
        }
    }

    private static Charset resolveCharset(String encoding) {
        try {
            return Charset.forName(Utils.normalizeEncoding(encoding));
        } catch (IllegalArgumentException e) {
            // fall through to the default; javac reports bad -encoding names
            return StandardCharsets.UTF_8;
        }
    }

    private static String format(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder out = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            if (diagnostic.getSource() != null && diagnostic.getLineNumber() >= 0) {
                out.append(diagnostic.getSource().getName())
                        .append(':').append(diagnostic.getLineNumber()).append(": ");
            }
            out.append(diagnostic.getMessage(null));
        }
        return out.toString().trim();
    }

    /** Runs the command, capturing its merged stdout/stderr. */
    static ProcessResult run(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(Utils.toByteArray(in), StandardCharsets.UTF_8);
        }
        try {
            return new ProcessResult(process.waitFor(), output.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("interrupted while waiting for javac: " + command, e);
        }
    }

    static final class ProcessResult {

        final int exit;
        final String output;

        private ProcessResult(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }
}
