package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;

/**
 * Runs the {@code auto-valhalla} annotation processor as a standalone
 * {@code javac -proc:only} selection pass over the project's source roots.
 * For every top-level {@code class}/{@code record} annotated with
 * {@code @AutoValhalla}, the processor writes an adapted copy of the source
 * file (with {@code value class}/{@code value record}) under the staging area
 * ({@code <buildDirectory>/auto-valhalla-jdk28/selected}), together with the
 * {@code selection.txt} manifest that this runner parses into its
 * {@link Selection} outcome.
 *
 * <p>A failed pass maps to an {@link IOException} that fails the build: the
 * processor only exits non-zero when it reported a real problem (e.g. an I/O
 * error writing the staged sources or the manifest).
 */
public final class AnnotationProcessorRunner {

    /** Name of the staging area under the build directory that receives the
     *  processor's adapted sources and selection manifest. */
    static final String STAGING_DIR = "auto-valhalla-jdk28";

    private AnnotationProcessorRunner() {
    }

    /**
     * Runs the selection pass over the input's source roots: the staging area
     * is recreated, the processor selects the {@code @AutoValhalla}-annotated
     * top-level types and stages their adapted copies, and the selection
     * manifest is parsed into the returned {@link Selection}. Nothing is
     * compiled and nothing is written under an output directory; the
     * {@code versionDirectory}, {@code outputDirectory} and
     * {@code compilerArgs} input fields are ignored.
     *
     * @param input the run inputs (source roots, build directory, javac,
     *              processor path, compile classpath, encoding)
     * @throws IOException on I/O errors during scanning or the selection pass
     */
    public static Selection run(Input input) throws IOException {
        File staging = new File(input.buildDirectory, STAGING_DIR);
        deleteRecursively(staging);
        File selected = selectedDir(staging);
        Files.createDirectories(selected.toPath());

        Selection selection = new Selection(selected);

        List<File> sources = collectSources(input.sourceRoots);
        if (sources.isEmpty()) {
            return selection;
        }

        runPass(input, sources, normalizeEncoding(input.encoding), selected);

        parseManifest(readManifest(selected), selection);
        return selection;
    }

    /** The outcome of a selection pass: which types were selected, which
     *  selected types the processor could not adapt (these fail the build),
     *  where the adapted copies were staged, and the staged files grouped by
     *  relative path for a follow-up compilation pass. */
    public static final class Selection {

        private final List<String> selectedTypes = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final Map<String, List<Adapted>> adaptedFiles = new LinkedHashMap<>();
        private final File stagedSources;

        private Selection(File stagedSources) {
            this.stagedSources = stagedSources;
        }

        /** Qualified names of the {@code @AutoValhalla}-annotated top-level
         *  types the processor selected (and staged adapted copies for). */
        public List<String> selectedTypes() {
            return selectedTypes;
        }

        /** Formatted {@code qname: reason} descriptions of the selected types
         *  the processor could not adapt. */
        public List<String> failures() {
            return failures;
        }

        /** Directory holding the staged adapted sources (the processor's out
         *  dir); created even when nothing was staged. */
        public File stagedSources() {
            return stagedSources;
        }

        /** Staged files by path relative to {@link #stagedSources()}; each
         *  entry lists the selected types living in that file. */
        public Map<String, List<Adapted>> adaptedFiles() {
            return adaptedFiles;
        }
    }

    /** An {@code ADAPTED} manifest line: one selected type and the staged file
     *  it lives in (relative to the selection out dir). */
    public static final class Adapted {

        private final String qname;
        private final String rel;

        private Adapted(String qname, String rel) {
            this.qname = qname;
            this.rel = rel;
        }

        /** Fully qualified name of the selected type. */
        public String qname() {
            return qname;
        }

        /** Staged file path relative to the selection out dir. */
        public String rel() {
            return rel;
        }
    }

    // -- selection pass ------------------------------------------------------

    /** Runs the processor's {@code javac -proc:only} selection pass. A failed
     *  pass maps to an {@link IOException} that fails the build: the processor
     *  only exits non-zero when it reported a real problem (e.g. an I/O error
     *  writing the staged sources or the manifest). */
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
        Javac.ProcessResult process = Javac.compile(input.fork, input.javac,
                options, sources, encoding);
        if (process.exit != 0) {
            throw new IOException("the auto-valhalla selection pass (javac -proc:only) failed:\n"
                    + process.output);
        }
    }

    private static void parseManifest(List<String> lines, Selection selection) {
        for (String line : lines) {
            Adapted adapted = parseAdapted(line);
            if (adapted != null) {
                selection.selectedTypes.add(adapted.qname);
                selection.adaptedFiles.computeIfAbsent(adapted.rel, k -> new ArrayList<>())
                        .add(adapted);
                continue;
            }
            SelectionFailure failure = parseFailure(line);
            if (failure != null) {
                selection.failures.add(failure.qname + ": " + failure.reason);
            }
        }
    }

    private static String normalizeEncoding(String encoding) {
        return (encoding != null && !encoding.trim().isEmpty())
                ? encoding.trim() : "UTF-8";
    }

    private static File selectedDir(File staging) {
        return new File(staging, "selected");
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

    private static Adapted parseAdapted(String line) {
        List<String> tokens = tokens(line);
        if (tokens.size() < 3 || !"ADAPTED".equals(tokens.get(0))) {
            return null;
        }
        return new Adapted(tokens.get(1), tokens.get(2));
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

    /** A {@code FAIL} manifest line: a selected type the processor could not
     *  adapt. */
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
}
