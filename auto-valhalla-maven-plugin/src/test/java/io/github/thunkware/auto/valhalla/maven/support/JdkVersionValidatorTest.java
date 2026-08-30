package io.github.thunkware.auto.valhalla.maven.support;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

class JdkVersionValidatorTest {

    @Test
    void allowsOlderMavenJdksWhenJava28HomeIsSet() throws Exception {
        JdkVersionValidator.validate(8, "/jdk28");
        JdkVersionValidator.validate(27, "/jdk28");
        JdkVersionValidator.validate(28, null);
    }

    @Test
    void rejectsOlderMavenJdksWithoutJava28Home() {
        assertThrows(MojoFailureException.class,
                () -> JdkVersionValidator.validate(27, null));
    }

    @Test
    void rejectsJdksNewerThanTwentyEight() {
        assertThrows(MojoFailureException.class,
                () -> JdkVersionValidator.validate(29, null));
    }
}
