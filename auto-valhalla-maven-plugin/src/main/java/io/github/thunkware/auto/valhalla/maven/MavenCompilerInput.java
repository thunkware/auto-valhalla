package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.project.MavenProject;

/**
 * Parameters for one invocation of the consuming project's
 * {@code maven-compiler-plugin}.
 */
final class MavenCompilerInput {

    final MavenSession session;
    final MavenProject project;
    final BuildPluginManager pluginManager;
    final List<String> sourceRoots;
    final File outputDirectory;
    final File buildDirectory;
    final String executable;
    final String processorPath;
    final List<String> compileClasspath;
    final String encoding;
    final List<String> compilerArgs;
    final String release;
    final boolean enablePreview;
    final String proc;
    final boolean skipProcessor;

    private MavenCompilerInput(Builder builder) {
        this.session = builder.session;
        this.project = builder.project;
        this.pluginManager = builder.pluginManager;
        this.sourceRoots = builder.sourceRoots;
        this.outputDirectory = builder.outputDirectory;
        this.buildDirectory = builder.buildDirectory;
        this.executable = builder.executable;
        this.processorPath = builder.processorPath;
        this.compileClasspath = builder.compileClasspath;
        this.encoding = builder.encoding;
        this.compilerArgs = builder.compilerArgs;
        this.release = builder.release;
        this.enablePreview = builder.enablePreview;
        this.proc = builder.proc;
        this.skipProcessor = builder.skipProcessor;
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {

        private MavenSession session;
        private MavenProject project;
        private BuildPluginManager pluginManager;
        private List<String> sourceRoots;
        private File outputDirectory;
        private File buildDirectory;
        private String executable;
        private String processorPath;
        private List<String> compileClasspath;
        private String encoding;
        private List<String> compilerArgs;
        private String release;
        private boolean enablePreview;
        private String proc;
        private boolean skipProcessor;

        Builder session(MavenSession session) {
            this.session = session;
            return this;
        }

        Builder project(MavenProject project) {
            this.project = project;
            return this;
        }

        Builder pluginManager(BuildPluginManager pluginManager) {
            this.pluginManager = pluginManager;
            return this;
        }

        Builder sourceRoots(List<String> sourceRoots) {
            this.sourceRoots = sourceRoots;
            return this;
        }

        Builder outputDirectory(File outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        Builder buildDirectory(File buildDirectory) {
            this.buildDirectory = buildDirectory;
            return this;
        }

        Builder executable(String executable) {
            this.executable = executable;
            return this;
        }

        Builder processorPath(String processorPath) {
            this.processorPath = processorPath;
            return this;
        }

        Builder compileClasspath(List<String> compileClasspath) {
            this.compileClasspath = compileClasspath;
            return this;
        }

        Builder encoding(String encoding) {
            this.encoding = encoding;
            return this;
        }

        Builder compilerArgs(List<String> compilerArgs) {
            this.compilerArgs = compilerArgs;
            return this;
        }

        Builder release(String release) {
            this.release = release;
            return this;
        }

        Builder enablePreview(boolean enablePreview) {
            this.enablePreview = enablePreview;
            return this;
        }

        Builder proc(String proc) {
            this.proc = proc;
            return this;
        }

        Builder skipProcessor(boolean skipProcessor) {
            this.skipProcessor = skipProcessor;
            return this;
        }

        MavenCompilerInput build() {
            if (session == null || project == null || pluginManager == null) {
                throw new IllegalStateException("Maven compiler context is required");
            }
            if (sourceRoots == null || sourceRoots.isEmpty() || outputDirectory == null) {
                throw new IllegalStateException("Maven compiler directories are required");
            }
            if (executable == null || executable.trim().isEmpty()) {
                throw new IllegalStateException("Maven compiler executable is required");
            }
            if (encoding == null || encoding.trim().isEmpty()) {
                throw new IllegalStateException("Maven compiler encoding is required");
            }
            return new MavenCompilerInput(this);
        }
    }
}
