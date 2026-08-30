package io.github.thunkware.auto.valhalla.maven.compiler;

import static io.github.thunkware.auto.valhalla.maven.compiler.Javac.ProcessResult;
import static java.util.Comparator.comparing;

import io.github.thunkware.auto.valhalla.maven.model.Generated;
import io.github.thunkware.auto.valhalla.maven.model.MavenCompilerInput;
import io.github.thunkware.auto.valhalla.maven.model.Selection;
import io.github.thunkware.auto.valhalla.maven.support.Utils;
import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.logging.Log;

/**
 * Runs the {@code auto-valhalla} annotation processor as a standalone
 * {@code javac -proc:only} selection pass over the project's source roots.
 * For every top-level {@code class}/{@code record} annotated with
 * {@code @AutoValhalla}, the processor writes a generated copy of the source
 * file (with {@code value class}/{@code value record}) under the generated dir
 * ({@code <buildDirectory>/auto-valhalla-generated-sources}).
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
    public static final String GENERATED_DIR = "auto-valhalla-generated-sources";

    private final MavenCompilerInvoker mavenCompilerInvoker;

    public AnnotationProcessorRunner(Log log) {
        this.mavenCompilerInvoker = new MavenCompilerInvoker(log);
    }

    /**
     * Runs the selection pass over the input's source roots: the generated dir
     * is recreated, the processor selects the {@code @AutoValhalla}-annotated
     * top-level types and generates their copies, and the generated source
     * files are collected into the returned {@link Selection}. Nothing is
     * compiled to a real output directory: the pass's {@code outputDirectory}
     * is only a scratch dir that keeps the compiler from skipping a project
     * whose classes are already up to date.
     *
     * @param input the compiler inputs (source roots, build directory, javac,
     *              processor path, compile classpath, encoding)
     * @throws IOException on I/O errors during scanning or the selection pass
     */
    public Selection run(MavenCompilerInput input) throws IOException {
        File generatedDir = input.generatedSourcesDirectory();

        Selection selection = new Selection(generatedDir);
        // don't delete generatedDir. mvn clean might not be run, and mvn compiler might skip if all source files are up-to-date
        Files.createDirectories(generatedDir.toPath());

        List<File> sources = collectSources(input.sourceRoots());
        if (sources.isEmpty()) {
            return selection;
        }

        // Files the pass rewrites get a fresh mtime; files that are still there
        // with an unchanged mtime were not regenerated and are stale leftovers
        // from an earlier source tree (e.g. an @AutoValhalla that was removed).
        // Dropping them keeps stale sources out of the multi-release versions dir.
        Map<File, Long> before = snapshotJavaFiles(generatedDir);
        runPass(input, generatedDir);
        pruneStaleGeneratedFiles(generatedDir, before);
        collectGeneratedFiles(generatedDir, selection);
        return selection;
    }

    public Selection findGeneratedFiles(MavenCompilerInput input) throws IOException {
        File generatedDir = input.generatedSourcesDirectory();

        Selection selection = new Selection(generatedDir);
        if (!generatedDir.isDirectory()) {
            return selection;
        }
        collectGeneratedFiles(generatedDir, selection);
        return selection;
    }

    // -- selection pass ------------------------------------------------------

    /**
     * Runs the processor's {@code javac -proc:only} selection pass. A failed
     * pass maps to an {@link IOException} that fails the build: the processor
     * only exits non-zero when it reported a real problem (e.g. an I/O error
     * writing the generated sources or the manifest).
     */
    private void runPass(MavenCompilerInput input, File selected) throws IOException {
        List<String> options = new ArrayList<>();
        options.add("-proc:only");
        options.add("-processorpath");
        options.add(input.processorPath());
        options.add("-cp");
        options.add(Javac.joinClasspath(input.compileClasspath()));
        options.add("-encoding");
        options.add(input.encoding());
        options.add("-A" + AutoValhallaProcessor.OPT_OUTDIR + "=" + selected.getAbsolutePath());
        if (input.removeAnnotation()) {
            options.add("-A" + AutoValhallaProcessor.OPT_REMOVE_ANNOTATION + "=true");
        }
        options.add("-processor");
        options.add(AutoValhallaProcessor.class.getName());
        MavenCompilerInput mavenCompilerInput = MavenCompilerInput.builder(input)
                // do not set outputDirectory. otherwise later default-compile shows irrelevant warnings
                .compilerArgs(options.subList(1, options.size()))
                .release(input.release())
                .enablePreview(input.enablePreview())
                .proc("only")
                .build();
        ProcessResult process = mavenCompilerInvoker.compile(mavenCompilerInput);
        if (process.exit != 0) {
            throw new IOException("the auto-valhalla selection pass "
                    + "(maven-compiler-plugin) failed:\n"
                    + process.output);
        }
    }

    private static void collectGeneratedFiles(File generatedDir, Selection selection)
            throws IOException {
        Utils.walkJavaFiles(generatedDir.toPath())
                .stream()
                .sorted()
                .forEach(path -> {
                    String rel = generatedDir.toPath().relativize(path).toString();
                    selection.selectedTypes.add(rel);
                    selection.generatedFiles.computeIfAbsent(rel, k -> new ArrayList<>())
                            .add(new Generated(rel, rel));
                });
    }

    private static Map<File, Long> snapshotJavaFiles(File dir) throws IOException {
        Map<File, Long> mtimes = new HashMap<>();
        if (!dir.isDirectory()) {
            return mtimes;
        }

        Utils.walkJavaFiles(dir.toPath())
                .forEach(path -> mtimes.put(path.toFile(), path.toFile().lastModified()));
        return mtimes;
    }

    private static void pruneStaleGeneratedFiles(File generatedDir, Map<File, Long> before)
            throws IOException {
        if (before.isEmpty()) {
            return;
        }
        List<Path> stale = new ArrayList<>();
        Utils.walkJavaFiles(generatedDir.toPath())
                .stream()
                .filter(path -> before.containsKey(path.toFile()))
                .filter(path -> path.toFile().lastModified() == before.get(path.toFile()))
                .forEach(stale::add);
        for (Path path : stale) {
            Files.deleteIfExists(path);
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
            Utils.walkJavaFiles(root.toPath())
                    .stream()
                    .filter(p -> !isMetaFile(p.getFileName().toString()))
                    .forEach(p -> files.add(p.toFile()));
        }
        Collections.sort(files, comparing(File::getAbsolutePath));
        return files;
    }

    private static boolean isMetaFile(String name) {
        return "module-info.java".equals(name) || "package-info.java".equals(name);
    }

}
