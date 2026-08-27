package demo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class ParentPluginConfigurationTest {
    @Test
    void inheritedCompilerAndJarPluginConfigurationIsAvailable() {
        assertTrue(Files.isRegularFile(new File(
                "target/classes/META-INF/versions/28/demo/Point.class").toPath()));
    }
}
