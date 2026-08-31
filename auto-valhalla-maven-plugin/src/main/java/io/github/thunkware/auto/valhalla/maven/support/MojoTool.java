package io.github.thunkware.auto.valhalla.maven.support;

import io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor;
import java.util.List;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

public final class MojoTool {

    private MojoTool() {
        throw new AssertionError();
    }

    public static List<String> getCompileClasspath(MavenProject project, boolean isTest) throws MojoExecutionException {
        try {
            return isTest ? project.getTestClasspathElements() : project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            String test = isTest ? "test" : "compile";
            throw new MojoExecutionException("auto-valhalla: could not resolve the project's "
                    + test + " classpath for javac", e);
        }
    }

    public static String getProcessorPath() throws MojoExecutionException {
        String processorPath = AutoValhallaProcessor.processorPath();
        if (processorPath == null) {
            throw new MojoExecutionException("auto-valhalla: could not locate the "
                    + "auto-valhalla-processor jar for javac's -processorpath");
        }
        return processorPath;
    }
}
