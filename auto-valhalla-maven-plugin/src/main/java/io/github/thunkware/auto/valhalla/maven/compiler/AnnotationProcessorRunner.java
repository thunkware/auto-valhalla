package io.github.thunkware.auto.valhalla.maven.compiler;

import static io.github.thunkware.auto.valhalla.maven.compiler.Javac.ProcessResult;

import io.github.thunkware.auto.valhalla.maven.model.Generated;
import io.github.thunkware.auto.valhalla.maven.model.MavenCompilerInput;
import io.github.thunkware.auto.valhalla.maven.model.Selection;
import io.github.thunkware.auto.valhalla.maven.mojo.CompileGeneratedSourcesMojo;
import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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

    private final MavenCompilerJavac mavenCompilerJavac;

    public AnnotationProcessorRunner(Log log) {
        this.mavenCompilerJavac = new MavenCompilerJavac(log);
    }

    /**
     * Runs the selection pass over the input's source roots: the generated dir
     * is recreated, the processor selects the {@code @AutoValhalla}-annotated
     * top-level types and generates their copies, and the generated source
     * files are collected into the returned {@link Selection}. Nothing is
     * compiled and nothing is written under an output directory; the
     * {@code versionDirectory}, {@code outputDirectory} and
     * {@code compilerArgs} input fields are ignored.
     *
     * <p>With {@code skipProcessor} set, the generated dir is not touched and
     * no pass runs: generated files left by a previous run are reused.
     *
     * @param input the compiler inputs (source roots, build directory, javac,
     *              processor path, compile classpath, encoding)
     * @throws IOException on I/O errors during scanning or the selection pass
     */
    public Selection run(MavenCompilerInput input) throws IOException {
        File generatedDir = new File(input.buildDirectory, GENERATED_DIR);

        Selection selection = new Selection(generatedDir);
        if (input.skipProcessor) {
            collectGeneratedFiles(generatedDir, selection);
            return selection;
        }

        // don't delete. mvn clean might not be run, and mvn compiler might skip if all source files are up-to-date
        // deleteRecursively(generatedDir);
        Files.createDirectories(generatedDir.toPath());

        List<File> sources = collectSources(input.sourceRoots);
        if (sources.isEmpty()) {
            return selection;
        }

        runPass(input, generatedDir);
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
        options.add(input.processorPath);
        options.add("-cp");
        options.add(Javac.joinClasspath(input.compileClasspath));
        options.add("-encoding");
        options.add(input.encoding);
        options.add("-A" + AutoValhallaProcessor.OPT_OUTDIR + "=" + selected.getAbsolutePath());
        options.add("-processor");
        options.add(AutoValhallaProcessor.class.getName());
        MavenCompilerInput mavenCompilerInput = MavenCompilerInput.builder()
                .session(input.session)
                .project(input.project)
                .pluginManager(input.pluginManager)
                .sourceRoots(input.sourceRoots)
                .outputDirectory(input.outputDirectory)
                .executable(input.executable)
                .encoding(input.encoding)
                .compilerArgs(options.subList(1, options.size()))
                .release(Integer.toString(CompileGeneratedSourcesMojo.MIN_VALHALLA_JDK))
                .enablePreview(true)
                .proc("only")
                .build();
        ProcessResult process = mavenCompilerJavac.compile(mavenCompilerInput);
        if (process.exit != 0) {
            throw new IOException("the auto-valhalla selection pass "
                    + "(maven-compiler-plugin) failed:\n"
                    + process.output);
        }
    }

    private static void collectGeneratedFiles(File generatedDir, Selection selection)
            throws IOException {
        try (Stream<Path> stream = Files.walk(generatedDir.toPath())) {
            stream.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> {
                        String rel = generatedDir.toPath().relativize(path).toString();
                        selection.selectedTypes.add(rel);
                        selection.generatedFiles.computeIfAbsent(rel, k -> new ArrayList<>())
                                .add(new Generated(rel, rel));
                    });
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

}
