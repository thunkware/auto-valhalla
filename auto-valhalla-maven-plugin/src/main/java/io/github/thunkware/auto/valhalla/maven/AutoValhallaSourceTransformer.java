package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.thunkware.auto.valhalla.maven.Utils.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.Utils.trim;
import static java.util.Collections.*;

/**
 * Compile-time transformation driver. It runs the {@code auto-valhalla}
 * annotation processor over the project's source roots (via
 * {@link AnnotationProcessorRunner}) to select the {@code @AutoValhalla}
 * -annotated top-level types and stage adapted copies of their source files
 * (with {@code value class}/{@code value record}), then compiles each staged
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
     * pass followed by compiling each staged file with the JDK compiler
     * ({@code --release <N> --enable-preview}), writing the resulting
     * value-class files under {@code META-INF/versions/<N>}.
     *
     * @param input the run inputs; {@code versionDirectory} and
     *              {@code outputDirectory} must be set
     * @throws IOException on I/O errors during scanning, staging, or compilation
     */
    public static Result transform(Input input) throws IOException {
        if (input.outputDirectory == null) {
            throw new IllegalStateException("outputDirectory is required for transform");
        }
        Result result = new Result();

        AnnotationProcessorRunner.Selection selection = AnnotationProcessorRunner.run(input);
        result.annotationFailures.addAll(selection.failures());
        result.selected.addAll(selection.selectedTypes());
        result.stagedSources = selection.stagedSources();
        if (selection.adaptedFiles().isEmpty()) {
            return result;
        }

        File versionedOut = new File(input.outputDirectory, "META-INF/versions/" + input.versionDirectory);
        String sourceEncoding = isNotBlank(input.encoding) ? trim(input.encoding) : "UTF-8";

        for (Map.Entry<String, List<AnnotationProcessorRunner.Adapted>> entry
                : selection.adaptedFiles().entrySet()) {
            Files.createDirectories(versionedOut.toPath());
            List<String> options = new ArrayList<>();
            options.add("--release");
            options.add(Integer.toString(input.versionDirectory));
            options.add("--enable-preview");
            options.add("-proc:none");
            options.add("-encoding");
            options.add(sourceEncoding);
            options.add("-cp");
            options.add(Javac.joinClasspath(input.compileClasspath));
            options.add("-d");
            options.add(versionedOut.getAbsolutePath());
            if (input.compilerArgs != null) {
                for (String arg : input.compilerArgs) {
                    if (isNotBlank(arg)) {
                        options.add(trim(arg));
                    }
                }
            }
            File stagedFile = new File(selection.stagedSources(), entry.getKey());
            Javac.ProcessResult process = Javac.compile(input.fork, input.javac,
                    options, singletonList(stagedFile), sourceEncoding);
            if (process.exit == 0) {
                result.converted += entry.getValue().size();
            } else {
                for (AnnotationProcessorRunner.Adapted adapted : entry.getValue()) {
                    result.annotationFailures.add(adapted.qname()
                            + ": javac reported:\n" + process.output);
                }
            }
        }
        return result;
    }
}
