package io.github.thunkware.auto.valhalla.maven.model;

import java.io.File;
import java.util.ArrayList;
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

    private final Builder builder;

    private MavenCompilerInput(Builder builder) {
        this.builder = builder;
    }

    public MavenSession session() {
        return builder.session;
    }

    public MavenProject project() {
        return builder.project;
    }

    public BuildPluginManager pluginManager() {
        return builder.pluginManager;
    }

    public List<String> sourceRoots() {
        return builder.sourceRoots;
    }

    public File outputDirectory() {
        return builder.outputDirectory;
    }

    public File buildDirectory() {
        return builder.buildDirectory;
    }

    public File generatedSourcesDirectory() {
        return builder.generatedSourcesDirectory;
    }

    public String executable() {
        return builder.executable;
    }

    public String processorPath() {
        return builder.processorPath;
    }

    public List<String> compileClasspath() {
        return builder.compileClasspath;
    }

    public String encoding() {
        return builder.encoding;
    }

    public List<String> compilerArgs() {
        return builder.compilerArgs;
    }

    public String release() {
        return builder.release;
    }

    public boolean enablePreview() {
        return builder.enablePreview;
    }

    public String proc() {
        return builder.proc;
    }

    public boolean removeAnnotation() {
        return builder.removeAnnotation;
    }

    public PlexusConfiguration compilerConfiguration() {
        return builder.compilerConfiguration;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(MavenCompilerInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Maven compiler input is required");
        }
        return input.builder.clone();
    }

    public static final class Builder implements Cloneable {

        private MavenSession session;
        private MavenProject project;
        private BuildPluginManager pluginManager;
        private List<String> sourceRoots;
        private File outputDirectory;
        private File buildDirectory;
        private File generatedSourcesDirectory;
        private String executable;
        private String processorPath;
        private List<String> compileClasspath;
        private String encoding;
        private List<String> compilerArgs;
        private String release;
        private boolean enablePreview;
        private String proc;
        private boolean removeAnnotation;
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
            this.sourceRoots = sourceRoots == null ? null : new ArrayList<>(sourceRoots);
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

        public Builder generatedSourcesDirectory(File generatedSourcesDirectory) {
            this.generatedSourcesDirectory = generatedSourcesDirectory;
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
            this.compileClasspath = compileClasspath == null ? null : new ArrayList<>(compileClasspath);
            return this;
        }

        public Builder encoding(String encoding) {
            this.encoding = encoding;
            return this;
        }

        public Builder compilerArgs(List<String> compilerArgs) {
            this.compilerArgs = compilerArgs == null ? null : new ArrayList<>(compilerArgs);
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

        public Builder removeAnnotation(boolean removeAnnotation) {
            this.removeAnnotation = removeAnnotation;
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
            if (generatedSourcesDirectory == null) {
                generatedSourcesDirectory = new File(buildDirectory, "auto-valhalla-generated-sources");
            }
            return new MavenCompilerInput(clone());
        }

        @Override
        public Builder clone() {
            try {
                Builder clone = (Builder) super.clone();
                clone.sourceRoots = sourceRoots == null ? null : new ArrayList<>(sourceRoots);
                clone.compileClasspath = compileClasspath == null ? null : new ArrayList<>(compileClasspath);
                clone.compilerArgs = compilerArgs == null ? null : new ArrayList<>(compilerArgs);
                return clone;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }
}
