package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Asserts what the auto-valhalla-maven-plugin did to this module at
 * {@code process-classes} (the {@code transform} goal): the selected classes
 * exist as value-class variants under {@code META-INF/versions/28} while the
 * base classes stay ordinary identity classes.
 *
 * <p>The produced class files are inspected with the JDK's own {@code javap},
 * so this test compiles at the same {@code --release} as the main sources and
 * needs no JDK 28-only API.
 */
class PluginOutputTest {

    private static final File JAVAP =
            new File(System.getProperty("java.home"), "bin/javap");

    @Test
    void versionedClassesAreValueClassesAndBaseClassesStayIdentity() throws Exception {
        Assumptions.assumeTrue(jdkFeature() >= 28,
                "value-class compilation (and reading its output) requires JDK 28+");

        String versionedPoint = javap("target/classes/META-INF/versions/28/demo/Point.class");
        assertTrue(versionedPoint.contains("value class demo.Point"),
                "versioned Point must be a value class:\n" + versionedPoint);
        assertTrue(versionedPoint.contains("major version: 72"));
        assertTrue(versionedPoint.contains("minor version: 65535"));
        assertFalse(versionedPoint.contains("ACC_SUPER"),
                "value classes are not identity classes (no ACC_SUPER)");

        String basePoint = javap("target/classes/demo/Point.class");
        assertTrue(basePoint.contains("major version: 61"),
                "base Point keeps its pre-Valhalla class file");
        assertTrue(basePoint.contains("ACC_SUPER"), "base Point stays an identity class");
        assertFalse(basePoint.contains("value class demo.Point"), "base Point must not be a value class");
    }

    private static String javap(String relativePath) throws Exception {
        Path file = Paths.get(relativePath);
        assertTrue(Files.isRegularFile(file), relativePath + " must exist");
        Path out = Files.createTempFile("javap-out-", ".txt");
        Path err = Files.createTempFile("javap-err-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder(
                    JAVAP.getAbsolutePath(), "-v", file.toAbsolutePath().toString())
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();
            int rc = process.waitFor();
            @SuppressWarnings("all")
            String stdout = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
            @SuppressWarnings("all")
            String stderr = new String(Files.readAllBytes(err), StandardCharsets.UTF_8);
            assertEquals(0, rc, "javap failed for " + relativePath + ":\n" + stdout + stderr);
            return stdout + stderr;
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    private static int jdkFeature() {
        String spec = System.getProperty("java.specification.version", "1.8");
        if (spec.startsWith("1.")) {
            spec = spec.substring(2);
        }
        try {
            return Integer.parseInt(spec);
        } catch (NumberFormatException e) {
            return 8;
        }
    }
}