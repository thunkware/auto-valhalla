package io.github.thunkware.auto.valhalla.maven;

import static java.util.Collections.singletonList;

import io.github.thunkware.auto.valhalla.maven.Javac.ProcessResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.logging.Log;

/**
 * Compile-time transformation driver. It runs the {@code auto-valhalla}
 * annotation processor over the project's source roots (via
 * {@link AnnotationProcessorRunner}) to select the {@code @AutoValhalla}
 * -annotated top-level types and generate copies of their source files
 * (with {@code value class}/{@code value record}), then compiles each generated
 * file with the JDK 28 compiler ({@code --release <N> --enable-preview}),
 * writing the resulting value-class files under {@code META-INF/versions/<N>}
 * so {@link MultiReleaseJarMojo} can mark the jar as multi-release.
 *
 * <p>There is no bytecode rewriting anywhere: the JVM's own compiler enforces
 * the value-class rules, and classes that javac rejects surface as per-source
 * failures.
 */
public final class AutoValhallaSourceTransformer {

    private final AnnotationProcessorRunner runner;
    private final MavenCompilerJavac mavenCompilerJavac;

    AutoValhallaSourceTransformer(Log log) {
        this.runner = new AnnotationProcessorRunner(log);
        this.mavenCompilerJavac = new MavenCompilerJavac(log);
    }

    /**
     * Runs the source-level transformation: the annotation-processor selection
     * pass followed by compiling each generated file with the JDK compiler
     * ({@code --release <N> --enable-preview}), writing the resulting
     * value-class files under {@code META-INF/versions/<N>}.
     *
     * @param input the run inputs; {@code outputDirectory} must be set
     * @throws IOException on I/O errors during scanning, generating, or compilation
     */
    public Result transform(Input input) throws IOException {
        if (input.outputDirectory == null) {
            throw new IllegalStateException("outputDirectory is required for transform");
        }
        Result result = new Result();

        Selection selection = runner.run(input);
        result.selected.addAll(selection.selectedTypes());
        result.generatedSources = selection.generatedSources();
        if (selection.generatedFiles().isEmpty()) {
            return result;
        }

        return withMavenCompilerPlugin(input, selection, result);
    }

    private static File getVersionsDirectory(Input input) {
        return new File(input.outputDirectory, "META-INF/versions/" + TransformMojo.MIN_VALHALLA_JDK);
    }

    private Result withMavenCompilerPlugin(Input input, Selection selection, Result result) throws IOException {
        File versionsDirectory = getVersionsDirectory(input);
        String sourceEncoding = Utils.normalizeEncoding(input.encoding);
        int generatedCount = 0;
        for (Map.Entry<String, List<Generated>> entry : selection.generatedFiles().entrySet()) {
            generatedCount += entry.getValue().size();
        }
        Files.createDirectories(versionsDirectory.toPath());
        MavenCompilerInput mavenCompilerInput = MavenCompilerInput.builder()
                .session(input.session)
                .project(input.project)
                .pluginManager(input.pluginManager)
                .sourceRoots(singletonList(selection.generatedSources().getAbsolutePath()))
                .outputDirectory(versionsDirectory)
                .executable(input.javac)
                .encoding(sourceEncoding)
                .release(Integer.toString(TransformMojo.MIN_VALHALLA_JDK))
                .enablePreview(true)
                .proc("none")
                .compilerArgs(input.compilerArgs)
                .build();
        ProcessResult process = mavenCompilerJavac.compile(mavenCompilerInput);
        if (process.exit == 0) {
            result.converted += generatedCount;
        } else {
            throw new IOException("maven-compiler-plugin failed:" + process.output);
        }
        return result;
    }
}
