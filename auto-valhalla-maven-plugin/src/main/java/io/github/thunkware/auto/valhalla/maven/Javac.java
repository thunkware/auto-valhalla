package io.github.thunkware.auto.valhalla.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
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
    }

    /** Joins classpath entries with the platform path separator. */
    static String joinClasspath(List<String> paths) {
        StringBuilder joined = new StringBuilder();
        for (String path : paths) {
            if (joined.length() > 0) {
                joined.append(File.pathSeparatorChar);
            }
            joined.append(path);
        }
        return joined.toString();
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
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.getDefault(), resolveCharset(encoding));
        try {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromFiles(files);
            boolean ok = compiler.getTask(null, fileManager, diagnostics,
                    options, null, units).call();
            return new ProcessResult(ok ? 0 : 1, format(diagnostics));
        } catch (RuntimeException e) {
            return new ProcessResult(1, format(diagnostics) + "\n" + e);
        } finally {
            try {
                fileManager.close();
            } catch (IOException e) {
                // closing the standard file manager cannot fail meaningfully
            }
        }
    }

    private static Charset resolveCharset(String encoding) {
        if (encoding != null && !encoding.trim().isEmpty()) {
            try {
                return Charset.forName(encoding.trim());
            } catch (IllegalArgumentException e) {
                // fall through to the default; javac reports bad -encoding names
            }
        }
        return StandardCharsets.UTF_8;
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
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
        try {
            return new ProcessResult(process.waitFor(), output.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for javac: " + e.getMessage(), e);
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
