package io.github.thunkware.auto.valhalla.maven.support;

import static io.github.thunkware.auto.valhalla.maven.CompileGeneratedSourcesMojo.MIN_MAVEN_JDK;
import static io.github.thunkware.auto.valhalla.maven.CompileGeneratedSourcesMojo.MIN_VALHALLA_JDK;
import static io.github.thunkware.auto.valhalla.maven.support.Utils.isNotBlank;

import io.github.thunkware.auto.valhalla.maven.CompileGeneratedSourcesMojo;
import org.apache.maven.plugin.MojoFailureException;

public final class JdkVersionValidator {

    public static void validate() throws MojoFailureException {
        validate(CompileGeneratedSourcesMojo.jdkFeature(), System.getenv("JAVA28_HOME"));
    }

    public static void validate(int feature, String java28Home)
            throws MojoFailureException {
        if (feature < MIN_MAVEN_JDK) {
            throw new MojoFailureException("auto-valhalla: Maven must run on JDK 8 through "
                    + MIN_VALHALLA_JDK + "; got JDK " + feature);
        }
        if (feature > MIN_VALHALLA_JDK) {
            throw new MojoFailureException("auto-valhalla: running on JDK " + feature
                    + " is unsupported; run Maven on JDK " + MIN_VALHALLA_JDK
                    + " or use JDK 8 through " + (MIN_VALHALLA_JDK - 1)
                    + " with JAVA28_HOME set");
        }
        if (feature < MIN_VALHALLA_JDK && !isNotBlank(java28Home)) {
            throw new MojoFailureException("auto-valhalla: running on JDK " + feature
                    + " requires JAVA28_HOME pointing to JDK 28");
        }
    }
}
