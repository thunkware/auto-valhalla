package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.util.List;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** Compiles transformed sources from the project's test source roots. */
@Mojo(name = "compile-generated-test-sources",
        defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true
)
public class CompileGeneratedTestSourcesMojo extends CompileGeneratedSourcesMojo {

    @Parameter(defaultValue = "${project.build.testOutputDirectory}", readonly = true, required = true)
    private File testOutputDirectory;

    @Parameter(defaultValue = "false", property = "auto-valhalla.skipCompileGeneratedTestSources")
    private boolean skipCompileGeneratedTestSources;

    @Override
    protected List<String> sourceRoots() {
        return project.getTestCompileSourceRoots();
    }

    @Override
    protected File generatedSourcesDirectory() {
        return new File(buildDirectory, "auto-valhalla-generated-test-sources");
    }

    @Override
    protected File outputDirectory() {
        return testOutputDirectory;
    }

    @Override
    protected boolean isSkipped() {
        return skipCompileGeneratedTestSources;
    }

    @Override
    protected boolean isTest() {
        return true;
    }
}
