package io.github.thunkware.auto.valhalla.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;

/**
 * Compile-time transformation driver. It runs the {@code auto-valhalla}
 * annotation processor with {@code javac -proc:only} over the project's source
 * roots to select the {@code @AutoValhalla}-annotated and {@code includes}-
 * matched top-level types and stage adapted copies of their source files
 * (with {@code value class}/{@code value record}), then compiles each staged
 * file with the JDK 28 compiler ({@code --release <N> --enable-preview}),
 * writing the resulting value-class files under {@code META-INF/versions/<N>}
 * so {@link MultiReleaseJarMojo} can mark the jar as multi-release.
 *
 * <p>There is no bytecode rewriting anywhere: the JVM's own compiler enforces
 * the value-class rules, and classes that javac rejects surface as per-source
 * failures. Selection mirrors the agent: {@code excludes} are checked first and
 * override everything; a type selected by both the annotation and
 * {@code includes} is treated as annotation-selected only.
 */
public final class AutoValhallaSourceTransformer {

    private AutoValhallaSourceTransformer() {
    }

    /** The outcome of a run: how many types were converted and which selected
     *  types javac (or the selection rules) rejected, grouped by selection
     *  source so the annotation-selected failures can fail the build while the
     *  includes-selected ones are normally skipped. */
    public static final class Result {

        private final List<String> annotationFailures = new ArrayList<>();
        private final List<String> includesFailures = new ArrayList<>();
        private int converted;

        private Result() {
        }

        public int convertedCount() {
            return converted;
        }

        public List<String> annotationFailures() {
            return annotationFailures;
        }

        public List<String> includesFailures() {
            return includesFailures;
        }
    }

    /**
     * Runs the source-level transformation.
     *
     * @param sourceRoots      directories containing the project's sources
     * @param includes         package/class patterns to convert (may be empty)
     * @param excludes         patterns never converted, checked first
     * @param versionDirectory the multi-release version directory (== the
     *                         {@code --release} the value classes target)
     * @param outputDirectory  compiled classes directory; versioned value classes
     *                         are written under {@code META-INF/versions/N}
     * @param buildDirectory   Maven {@code target} directory, for the staging area
     * @param javac            the JDK 28 {@code javac} executable
     * @param processorPath    {@code -processorpath} for the auto-valhalla
     *                         processor (its jar or class directory)
     * @param compileClasspath the project's compile classpath passed to javac
     * @throws IOException on I/O errors during scanning, staging, or compilation
     */
    public static Result transform(List<String> sourceRoots, List<String> includes,
            List<String> excludes, int versionDirectory, File outputDirectory,
            File buildDirectory, String javac, String processorPath,
            List<String> compileClasspath) throws IOException {
        Result result = new Result();
        File staging = new File(buildDirectory, "auto-valhalla-jdk28");
        deleteRecursively(staging);
        File selected = new File(staging, "selected");
        Files.createDirectories(selected.toPath());
        File versionedOut = new File(outputDirectory,
                "META-INF/versions/" + versionDirectory);

        List<File> sources = collectSources(sourceRoots);
        if (sources.isEmpty()) {
            return result;
        }

        runSelectionPass(javac, processorPath, includes, excludes, selected,
                compileClasspath, sources);

        List<String> manifest = readManifest(selected);
        Map<String, List<Adapted>> adaptedFiles = new LinkedHashMap<>();
        for (String line : manifest) {
            Adapted adapted = parseAdapted(line);
            if (adapted != null) {
                adaptedFiles.computeIfAbsent(adapted.rel, k -> new ArrayList<>()).add(adapted);
                continue;
            }
            SelectionFailure failure = parseFailure(line);
            if (failure != null) {
                fail(result, failure.bucket, failure.qname, failure.reason);
            }
        }

        for (Map.Entry<String, List<Adapted>> entry : adaptedFiles.entrySet()) {
            Files.createDirectories(versionedOut.toPath());
            List<String> command = new ArrayList<>();
            command.add(javac);
            command.add("--release");
            command.add(Integer.toString(versionDirectory));
            command.add("--enable-preview");
            command.add("-proc:none");
            command.add("-encoding");
            command.add("UTF-8");
            command.add("-cp");
            command.add(joinPathSeparator(compileClasspath));
            command.add("-d");
            command.add(versionedOut.getAbsolutePath());
            command.add(new File(selected, entry.getKey()).getAbsolutePath());
            ProcessResult process = run(command);
            if (process.exit == 0) {
                result.converted += entry.getValue().size();
            } else {
                for (Adapted adapted : entry.getValue()) {
                    fail(result, adapted.bucket, adapted.qname,
                            "javac reported:\n" + process.output);
                }
            }
        }
        return result;
    }

    /** Runs the processor's {@code javac -proc:only} selection pass and maps a
     *  failed pass without a manifest to an {@link IOException} (an internal
     *  error rather than a per-source failure). */
    private static void runSelectionPass(String javac, String processorPath,
            List<String> includes, List<String> excludes, File selected,
            List<String> compileClasspath, List<File> sources) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javac);
        command.add("-proc:only");
        command.add("-processorpath");
        command.add(processorPath);
        command.add("-cp");
        command.add(joinPathSeparator(compileClasspath));
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-A" + AutoValhallaProcessor.OPT_INCLUDES + "=" + joinCommas(includes));
        command.add("-A" + AutoValhallaProcessor.OPT_EXCLUDES + "=" + joinCommas(excludes));
        command.add("-A" + AutoValhallaProcessor.OPT_OUTDIR + "=" + selected.getAbsolutePath());
        for (File file : sources) {
            command.add(file.getAbsolutePath());
        }
        ProcessResult process = run(command);
        if (process.exit != 0 && !new File(selected, AutoValhallaProcessor.SELECTION_FILE).exists()) {
            throw new IOException("the auto-valhalla selection pass (javac -proc:only) failed:\n"
                    + process.output);
        }
    }

    // -- selection manifest ------------------------------------------------

    private static List<String> readManifest(File selected) throws IOException {
        Path manifest = selected.toPath().resolve(AutoValhallaProcessor.SELECTION_FILE);
        if (!Files.exists(manifest)) {
            throw new IOException("the auto-valhalla selection pass produced no "
                    + AutoValhallaProcessor.SELECTION_FILE + " manifest");
        }
        String content = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (!line.trim().isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Adapted parseAdapted(String line) {
        List<String> tokens = tokens(line);
        if (tokens.size() < 4 || !"ADAPTED".equals(tokens.get(0))) {
            return null;
        }
        return new Adapted(tokens.get(1), tokens.get(2), tokens.get(3));
    }

    private static SelectionFailure parseFailure(String line) {
        List<String> tokens = tokens(line);
        if (tokens.size() < 4 || !"FAIL".equals(tokens.get(0))) {
            return null;
        }
        int space = line.indexOf(' ');
        int second = line.indexOf(' ', space + 1);
        int third = line.indexOf(' ', second + 1);
        return new SelectionFailure(tokens.get(1), tokens.get(2),
                third < 0 ? "" : line.substring(third + 1));
    }

    private static List<String> tokens(String line) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < line.length() && !Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i > start) {
                tokens.add(line.substring(start, i));
            }
        }
        return tokens;
    }

    private static void fail(Result result, String bucket, String qname, String reason) {
        if ("annotated".equals(bucket)) {
            result.annotationFailures.add(qname + ": " + reason);
        } else {
            result.includesFailures.add(qname + ": " + reason);
        }
    }

    /** An {@code ADAPTED} manifest line: one selected type, the staged file it
     *  lives in (relative to the selection out dir), and its selection bucket. */
    private static final class Adapted {

        private final String bucket;
        private final String qname;
        private final String rel;

        private Adapted(String bucket, String qname, String rel) {
            this.bucket = bucket;
            this.qname = qname;
            this.rel = rel;
        }
    }

    /** A {@code FAIL} manifest line: a selected type the processor could not
     *  adapt, bucketed like a javac rejection. */
    private static final class SelectionFailure {

        private final String bucket;
        private final String qname;
        private final String reason;

        private SelectionFailure(String bucket, String qname, String reason) {
            this.bucket = bucket;
            this.qname = qname;
            this.reason = reason;
        }
    }

    // -- file scanning -------------------------------------------------------

    private static List<File> collectSources(List<String> sourceRoots) throws IOException {
        List<File> files = new ArrayList<>();
        for (String rootPath : sourceRoots) {
            File root = new File(rootPath);
            if (!root.isDirectory()) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root.toPath())) {
                stream.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !isMetaFile(p.getFileName().toString()))
                        .sorted()
                        .forEach(p -> files.add(p.toFile()));
            }
        }
        return files;
    }

    private static boolean isMetaFile(String name) {
        return "module-info.java".equals(name) || "package-info.java".equals(name);
    }

    private static void deleteRecursively(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteRecursively(child);
                } else {
                    child.delete();
                }
            }
        }
        dir.delete();
    }

    private static String joinPathSeparator(List<String> paths) {
        StringBuilder joined = new StringBuilder();
        for (String path : paths) {
            if (joined.length() > 0) {
                joined.append(File.pathSeparatorChar);
            }
            joined.append(path);
        }
        return joined.toString();
    }

    private static String joinCommas(List<String> patterns) {
        StringBuilder joined = new StringBuilder();
        for (String pattern : patterns) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(pattern);
        }
        return joined.toString();
    }

    private static ProcessResult run(List<String> command) throws IOException {
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

    private static final class ProcessResult {

        private final int exit;
        private final String output;

        private ProcessResult(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }
}