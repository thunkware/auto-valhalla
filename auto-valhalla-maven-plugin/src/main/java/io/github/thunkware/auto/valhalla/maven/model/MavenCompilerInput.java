package io.github.thunkware.auto.valhalla.maven.model;

import java.io.File;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.configuration.PlexusConfiguration;

/**
 * Parameters for one invocation of the consuming project's
 * {@code maven-compiler-plugin}.
 */
public final class MavenCompilerInput {

    public final MavenSession session;
    public final MavenProject project;
    public final BuildPluginManager pluginManager;
    public final List<String> sourceRoots;
    public final File outputDirectory;
    public final File buildDirectory;
    public final String executable;
    public final String processorPath;
    public final List<String> compileClasspath;
    public final String encoding;
    public final List<String> compilerArgs;
    public final String release;
    public final boolean enablePreview;
    public final String proc;
    public final boolean skipProcessor;
    public final PlexusConfiguration compilerConfiguration;

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
        this.compilerConfiguration = builder.compilerConfiguration;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

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
        private PlexusConfiguration compilerConfiguration;

        public Builder session(MavenSession session) {
            this.session = session;
            return this;
        }

        public Builder project(MavenProject project) {
            this.project = project;
            return this;
        }

        public Builder pluginManager(BuildPluginManager pluginManager) {
            this.pluginManager = pluginManager;
            return this;
        }

        public Builder sourceRoots(List<String> sourceRoots) {
            this.sourceRoots = sourceRoots;
            return this;
        }

        public Builder outputDirectory(File outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        public Builder buildDirectory(File buildDirectory) {
            this.buildDirectory = buildDirectory;
            return this;
        }

        public Builder executable(String executable) {
            this.executable = executable;
            return this;
        }

        public Builder processorPath(String processorPath) {
            this.processorPath = processorPath;
            return this;
        }

        public Builder compileClasspath(List<String> compileClasspath) {
            this.compileClasspath = compileClasspath;
            return this;
        }

        public Builder encoding(String encoding) {
            this.encoding = encoding;
            return this;
        }

        public Builder compilerArgs(List<String> compilerArgs) {
            this.compilerArgs = compilerArgs;
            return this;
        }

        public Builder release(String release) {
            this.release = release;
            return this;
        }

        public Builder enablePreview(boolean enablePreview) {
            this.enablePreview = enablePreview;
            return this;
        }

        public Builder proc(String proc) {
            this.proc = proc;
            return this;
        }

        public Builder skipProcessor(boolean skipProcessor) {
            this.skipProcessor = skipProcessor;
            return this;
        }

        public Builder compilerConfiguration(PlexusConfiguration compilerConfiguration) {
            this.compilerConfiguration = compilerConfiguration;
            return this;
        }

        public MavenCompilerInput build() {
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
