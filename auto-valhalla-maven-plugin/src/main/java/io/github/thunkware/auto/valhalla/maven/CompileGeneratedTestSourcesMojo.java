package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.util.List;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Compiles transformed sources from the project's test source roots. */
@Mojo(name = "compile-generated-test-sources",
        defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
        threadSafe = true
)
public class CompileGeneratedTestSourcesMojo extends CompileGeneratedSourcesMojo {

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
    protected File outputDirectory() {
        return testOutputDirectory;
    }

    @Parameter(defaultValue = "${project.build.testOutputDirectory}", readonly = true, required = true)
    private File testOutputDirectory;
}
