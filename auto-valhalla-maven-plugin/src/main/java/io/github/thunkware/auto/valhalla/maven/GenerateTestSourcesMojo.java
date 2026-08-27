package io.github.thunkware.auto.valhalla.maven;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_TEST_SOURCES;

import java.io.File;
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Generates transformed sources from the project's test source roots. */
@Mojo(name = "generate-test-sources",
        defaultPhase = GENERATE_TEST_SOURCES,
        threadSafe = true)
public class GenerateTestSourcesMojo extends GenerateSourcesMojo {

    @Parameter(defaultValue = "false", property = "auto-valhalla.skipGenerateTestSources")
    private boolean skipGenerateTestSources;

    @Override
    protected List<String> sourceRoots() {
        return project.getTestCompileSourceRoots();
    }

    @Override
    protected File generatedSourcesDirectory() {
        return new File(buildDirectory, "auto-valhalla-generated-test-sources");
    }

    @Override
    protected List<String> compileClasspath() throws org.apache.maven.artifact.DependencyResolutionRequiredException {
        return project.getTestClasspathElements();
    }

    @Override
    protected boolean isSkipped() {
        return skipGenerateTestSources;
    }

    protected void setSkipGenerateTestSources(boolean skipGenerateTestSources) {
        this.skipGenerateTestSources = skipGenerateTestSources;
    }
}
