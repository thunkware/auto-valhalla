package io.github.thunkware.auto.valhalla.maven;

import static io.github.thunkware.auto.valhalla.maven.Utils.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.Utils.trim;
import static java.util.Collections.singletonList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private AutoValhallaSourceTransformer() {
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
    public static Result transform(Input input) throws IOException {
        if (input.outputDirectory == null) {
            throw new IllegalStateException("outputDirectory is required for transform");
        }
        Result result = new Result();

        Selection selection = AnnotationProcessorRunner.run(input);
        result.annotationFailures.addAll(selection.failures());
        result.selected.addAll(selection.selectedTypes());
        result.generatedSources = selection.generatedSources();
        if (selection.generatedFiles().isEmpty()) {
            return result;
        }

        if (input.useMavenCompiler) {
            return withMavenCompilerPlugin(input, selection, result);
        }

        File versionsDirectory = getVersionsDirectory(input);
        for (Map.Entry<String, List<Generated>> entry
                : selection.generatedFiles().entrySet()) {
            Files.createDirectories(versionsDirectory.toPath());
            List<String> options = new ArrayList<>();
            options.add("--release");
            options.add(Integer.toString(TransformMojo.MIN_VALHALLA_JDK));
            options.add("--enable-preview");
            options.add("-proc:none");
            options.add("-encoding");
            options.add(input.encoding);
            options.add("-cp");
            options.add(Javac.joinClasspath(input.compileClasspath));
            options.add("-d");
            options.add(versionsDirectory.getAbsolutePath());
            if (input.compilerArgs != null) {
                for (String arg : input.compilerArgs) {
                    if (isNotBlank(arg)) {
                        options.add(trim(arg));
                    }
                }
            }
            File generatedFile = new File(selection.generatedSources(), entry.getKey());
            Javac.ProcessResult process = Javac.compile(input, options, singletonList(generatedFile));
            if (process.exit == 0) {
                result.converted += entry.getValue().size();
            } else {
                for (Generated generated : entry.getValue()) {
                    result.annotationFailures.add(generated.qname()
                            + ": javac reported:\n" + process.output);
                }
            }
        }
        return result;
    }

    private static File getVersionsDirectory(Input input) {
        return new File(input.outputDirectory, "META-INF/versions/" + TransformMojo.MIN_VALHALLA_JDK);
    }

    private static Result withMavenCompilerPlugin(Input input, Selection selection, Result result) throws IOException {
        File versionsDirectory = getVersionsDirectory(input);
        String sourceEncoding = Utils.normalizeEncoding(input.encoding);
        int generatedCount = 0;
        for (Map.Entry<String, List<Generated>> entry
                : selection.generatedFiles().entrySet()) {
            generatedCount += entry.getValue().size();
        }
        Files.createDirectories(versionsDirectory.toPath());
        Javac.ProcessResult process = MavenCompilerJavac.compile(
                MavenCompilerInput.builder()
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
                        .build());
        if (process.exit == 0) {
            result.converted += generatedCount;
        } else {
            for (Map.Entry<String, List<Generated>> entry
                    : selection.generatedFiles().entrySet()) {
                for (Generated generated : entry.getValue()) {
                    result.annotationFailures.add(generated.qname()
                            + ": maven-compiler-plugin reported:\n"
                            + process.output);
                }
            }
        }
        return result;
    }
}
