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
    final int versionDirectory;
    final File outputDirectory;
    final File buildDirectory;
    final String javac;
    final String processorPath;
    final List<String> compileClasspath;
    final String encoding;
    final List<String> compilerArgs;

    private Input(Builder builder) {
        this.sourceRoots = builder.sourceRoots;
        this.versionDirectory = builder.versionDirectory;
        this.outputDirectory = builder.outputDirectory;
        this.buildDirectory = builder.buildDirectory;
        this.javac = builder.javac;
        this.processorPath = builder.processorPath;
        this.compileClasspath = builder.compileClasspath;
        this.encoding = builder.encoding;
        this.compilerArgs = builder.compilerArgs;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link Input}.
     */
    public static final class Builder {

        private List<String> sourceRoots;
        private int versionDirectory = 28;
        private File outputDirectory;
        private File buildDirectory;
        private String javac;
        private String processorPath;
        private List<String> compileClasspath;
        private String encoding = "UTF-8";
        private List<String> compilerArgs = java.util.Collections.emptyList();

        /**
         * Directories containing the project's sources.
         */
        public Builder sourceRoots(List<String> sourceRoots) {
            this.sourceRoots = sourceRoots;
            return this;
        }

        /**
         * The multi-release version directory ({@code META-INF/versions/<N>});
         * also the {@code --release} the value classes target.
         */
        public Builder versionDirectory(int versionDirectory) {
            this.versionDirectory = versionDirectory;
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
         * Maven {@code target} directory, for the staging area.
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
