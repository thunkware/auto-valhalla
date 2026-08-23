package io.github.thunkware.auto.valhalla.maven;

import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the {@code auto-valhalla} annotation processor as a standalone
 * {@code javac -proc:only} selection pass over the project's source roots.
 * For every top-level {@code class}/{@code record} annotated with
 * {@code @AutoValhalla}, the processor writes a generated copy of the source
 * file (with {@code value class}/{@code value record}) under the generated dir
 * ({@code <buildDirectory>/auto-valhalla-generated-sources/selected}), together with the
 * {@code selection.txt} manifest that this runner parses into its
 * {@link Selection} outcome.
 *
 * <p>A failed pass maps to an {@link IOException} that fails the build: the
 * processor only exits non-zero when it reported a real problem (e.g. an I/O
 * error writing the generated sources or the manifest).
 */
public final class AnnotationProcessorRunner {

    /**
     * Name of the generated dir under the build directory that receives the
     * processor's generated sources and selection manifest.
     */
    static final String GENERATED_DIR = "auto-valhalla-generated-sources";

    private AnnotationProcessorRunner() {
    }

    /**
     * Runs the selection pass over the input's source roots: the generated dir
     * is recreated, the processor selects the {@code @AutoValhalla}-annotated
     * top-level types and generates their copies, and the selection
     * manifest is parsed into the returned {@link Selection}. Nothing is
     * compiled and nothing is written under an output directory; the
     * {@code versionDirectory}, {@code outputDirectory} and
     * {@code compilerArgs} input fields are ignored.
     *
     * <p>With {@code skipProcessor} set, the generated dir is not touched and
     * no pass runs: the manifest left by a previous run (e.g. a prior
     * {@code process-sources} execution) is parsed as-is, which fails when it
     * does not exist.
     *
     * @param input the run inputs (source roots, build directory, javac,
     *              processor path, compile classpath, encoding)
     * @throws IOException on I/O errors during scanning or the selection pass
     */
    static Selection run(Input input) throws IOException {
        File generatedDir = new File(input.buildDirectory, GENERATED_DIR);
        File selected = selectedDir(generatedDir);

        Selection selection = new Selection(selected);
        if (input.skipProcessor) {
            parseManifest(readManifest(selected), selection);
            return selection;
        }

        deleteRecursively(generatedDir);
        Files.createDirectories(selected.toPath());

        List<File> sources = collectSources(input.sourceRoots);
        if (sources.isEmpty()) {
            return selection;
        }

        runPass(input, sources, input.encoding, selected);

        parseManifest(readManifest(selected), selection);
        return selection;
    }

    // -- selection pass ------------------------------------------------------

    /**
     * Runs the processor's {@code javac -proc:only} selection pass. A failed
     * pass maps to an {@link IOException} that fails the build: the processor
     * only exits non-zero when it reported a real problem (e.g. an I/O error
     * writing the generated sources or the manifest).
     */
    private static void runPass(Input input, List<File> sources, String encoding,
                                File selected) throws IOException {
        List<String> options = new ArrayList<>();
        options.add("-proc:only");
        options.add("-processorpath");
        options.add(input.processorPath);
        options.add("-cp");
        options.add(Javac.joinClasspath(input.compileClasspath));
        options.add("-encoding");
        options.add(encoding);
        options.add("-A" + AutoValhallaProcessor.OPT_OUTDIR + "=" + selected.getAbsolutePath());
        if (input.useMavenCompiler) {
            withMavenCompiler(input, encoding, options);
            return;
        }
        Javac.ProcessResult process = Javac.compile(input, options, sources);
        if (process.exit != 0) {
            throw new IOException("the auto-valhalla selection pass (javac -proc:only) failed:\n"
                    + process.output);
        }
    }

    private static void withMavenCompiler(Input input, String encoding, List<String> options) throws IOException {
        options.add("-processor");
        options.add(AutoValhallaProcessor.class.getName());
        MavenCompilerInput mavenCompilerInput = MavenCompilerInput.builder()
                .session(input.session)
                .project(input.project)
                .pluginManager(input.pluginManager)
                .sourceRoots(input.sourceRoots)
                .outputDirectory(input.buildDirectory)
                .executable(input.javac)
                .encoding(encoding)
                .compilerArgs(options.subList(1, options.size()))
                .release(Integer.toString(TransformMojo.MIN_VALHALLA_JDK))
                .enablePreview(true)
                .proc("only")
                .build();
        Javac.ProcessResult process = MavenCompilerJavac.compile(mavenCompilerInput);
        if (process.exit != 0) {
            throw new IOException("the auto-valhalla selection pass "
                    + "(maven-compiler-plugin) failed:\n" + process.output);
        }
    }

    private static void parseManifest(List<String> lines, Selection selection) {
        for (String line : lines) {
            Generated generated = parseGenerated(line);
            if (generated != null) {
                selection.selectedTypes.add(generated.qname);
                selection.generatedFiles.computeIfAbsent(generated.rel, k -> new ArrayList<>())
                        .add(generated);
                continue;
            }
            SelectionFailure failure = parseFailure(line);
            if (failure != null) {
                selection.failures.add(failure.qname + ": " + failure.reason);
            }
        }
    }

    private static File selectedDir(File generatedDir) {
        return new File(generatedDir, "selected");
    }

    // -- selection manifest ------------------------------------------------

    private static List<String> readManifest(File selected) throws IOException {
        Path manifest = selected.toPath().resolve(AutoValhallaProcessor.SELECTION_FILE);
        if (!Files.exists(manifest)) {
            throw new IOException("the auto-valhalla selection pass produced no "
                    + AutoValhallaProcessor.SELECTION_FILE + " manifest");
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(manifest));
        lines.removeIf(line -> line.trim().isEmpty());
        return lines;
    }

    private static Generated parseGenerated(String line) {
        List<String> tokens = tokens(line);
        if (tokens.size() < 3 || !"GENERATED".equals(tokens.get(0))) {
            return null;
        }
        return new Generated(tokens.get(1), tokens.get(2));
    }

    private static SelectionFailure parseFailure(String line) {
        List<String> tokens = tokens(line);
        if (tokens.size() < 3 || !"FAIL".equals(tokens.get(0))) {
            return null;
        }
        int space = line.indexOf(' ');
        int second = line.indexOf(' ', space + 1);
        return new SelectionFailure(tokens.get(1),
                second < 0 ? "" : line.substring(second + 1));
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

    /**
     * A {@code FAIL} manifest line: a selected type the processor could not
     * generate.
     */
    private static final class SelectionFailure {

        private final String qname;
        private final String reason;

        private SelectionFailure(String qname, String reason) {
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

    private static void deleteRecursively(File dir) throws IOException {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteRecursively(child);
                } else {
                    Files.delete(child.toPath());
                }
            }
        }
        Files.delete(dir.toPath());
    }
}
