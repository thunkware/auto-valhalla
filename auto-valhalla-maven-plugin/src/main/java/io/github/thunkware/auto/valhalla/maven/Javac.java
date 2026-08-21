package io.github.thunkware.auto.valhalla.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs {@code javac} processes; helpers shared by the selection pass
 * ({@link AnnotationProcessorRunner}) and the value-class compilation pass
 * ({@link AutoValhallaSourceTransformer}).
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
