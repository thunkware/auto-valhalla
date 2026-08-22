package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.util.List;

/**
 * The inputs of a run, shared by {@link AnnotationProcessorRunner#run(Input)}
 * and {@link AutoValhallaSourceTransformer#transform(Input)}. Built with the
 * fluent {@link Builder}: {@code Input.builder()...build()}. The source roots,
 * build directory, javac executable, processor path and compile classpath are
 * required; {@code encoding} defaults to UTF-8 and {@code compilerArgs} to
 * none. {@code versionDirectory} and {@code outputDirectory} are only used by
 * {@link AutoValhallaSourceTransformer#transform(Input)}.
 */
public final class Input {

    final List<String> sourceRoots;
    final File outputDirectory;
    final File buildDirectory;
    final String javac;
    final String processorPath;
    final List<String> compileClasspath;
    final String encoding;
    final List<String> compilerArgs;
    final boolean fork;
    final boolean skipProcessor;

    private Input(Builder builder) {
        this.sourceRoots = builder.sourceRoots;
        this.outputDirectory = builder.outputDirectory;
        this.buildDirectory = builder.buildDirectory;
        this.javac = builder.javac;
        this.processorPath = builder.processorPath;
        this.compileClasspath = builder.compileClasspath;
        this.encoding = builder.encoding;
        this.compilerArgs = builder.compilerArgs;
        this.fork = builder.fork;
        this.skipProcessor = builder.skipProcessor;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link Input}.
     */
    public static final class Builder {

        private List<String> sourceRoots;
        private File outputDirectory;
        private File buildDirectory;
        private String javac;
        private String processorPath;
        private List<String> compileClasspath;
        private String encoding = "UTF-8";
        private List<String> compilerArgs = java.util.Collections.emptyList();
        private boolean fork = true;
        private boolean skipProcessor;

        /**
         * Directories containing the project's sources.
         */
        public Builder sourceRoots(List<String> sourceRoots) {
            this.sourceRoots = sourceRoots;
            return this;
        }

        /**
         * Compiled classes directory; versioned value classes are written
         * under {@code META-INF/versions/N} here.
         */
        public Builder outputDirectory(File outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        /**
         * Maven {@code target} directory, for the generated dir.
         */
        public Builder buildDirectory(File buildDirectory) {
            this.buildDirectory = buildDirectory;
            return this;
        }

        /**
         * The {@code javac} executable for both passes.
         */
        public Builder javac(String javac) {
            this.javac = javac;
            return this;
        }

        /**
         * {@code -processorpath} for the auto-valhalla processor (its jar
         * or class directory).
         */
        public Builder processorPath(String processorPath) {
            this.processorPath = processorPath;
            return this;
        }

        /**
         * The project's compile classpath passed to javac.
         */
        public Builder compileClasspath(List<String> compileClasspath) {
            this.compileClasspath = compileClasspath;
            return this;
        }

        /**
         * The source encoding to use for javac; defaults to UTF-8.
         */
        public Builder encoding(String encoding) {
            this.encoding = encoding;
            return this;
        }

        /**
         * Additional compiler arguments forwarded to javac (compilation
         * pass only).
         */
        public Builder compilerArgs(List<String> compilerArgs) {
            this.compilerArgs = compilerArgs;
            return this;
        }

        /**
         * Whether to run javac as a forked process (the default) or
         * in-process through the {@code javax.tools.JavaCompiler} API. When
         * false, the {@code javac} executable override is ignored and the
         * JDK running Maven does the compiling.
         */
        public Builder fork(boolean fork) {
            this.fork = fork;
            return this;
        }

        /**
         * Whether to skip the annotation-processor selection pass and reuse
         * the generated dir from a previous run; only meaningful for
         * {@link AutoValhallaSourceTransformer#transform(Input)}.
         */
        public Builder skipProcessor(boolean skipProcessor) {
            this.skipProcessor = skipProcessor;
            return this;
        }

        public Input build() {
            if (sourceRoots == null || sourceRoots.isEmpty()) {
                throw new IllegalStateException("sourceRoots is required");
            }
            if (buildDirectory == null) {
                throw new IllegalStateException("buildDirectory is required");
            }
            if (javac == null || javac.trim().isEmpty()) {
                throw new IllegalStateException("javac is required");
            }
            if (processorPath == null || processorPath.trim().isEmpty()) {
                throw new IllegalStateException("processorPath is required");
            }
            if (compileClasspath == null) {
                throw new IllegalStateException("compileClasspath is required");
            }
            return new Input(this);
        }
    }
}
