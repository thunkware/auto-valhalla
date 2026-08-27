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
 * Asserts the {@code removeAnnotation} configuration of this module: the
 * {@code generate-sources} goal strips the {@code @AutoValhalla} marker from
 * the generated value-class sources (the base sources keep it), and the
 * value-class variant is still produced under {@code META-INF/versions/28}
 * while the base class stays an ordinary identity class.
 */
class PluginOutputTest {

    private static final File JAVAP =
            new File(System.getProperty("java.home"), "bin/javap");

    @Test
    void generatedSourcesHaveAnnotationRemoved() throws Exception {
        Path generated = Paths.get("target/auto-valhalla-generated-sources/demo/Point.java");
        assertTrue(Files.isRegularFile(generated), generated + " must exist");
        String copy = new String(Files.readAllBytes(generated), StandardCharsets.UTF_8);
        assertTrue(copy.contains("public final value class Point"),
                "generated copy must still be a value class:\n" + copy);
        assertFalse(copy.contains("@AutoValhalla"),
                "generated copy must not carry the @AutoValhalla marker:\n" + copy);

        Path original = Paths.get("src/main/java/demo/Point.java");
        String baseSource = new String(Files.readAllBytes(original), StandardCharsets.UTF_8);
        assertTrue(baseSource.contains("@AutoValhalla"),
                "the base source must keep the @AutoValhalla marker:\n" + baseSource);
    }

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
        assertTrue(basePoint.contains("major version: 52"),
                "base Point keeps its pre-Valhalla class file (compiled at release 8)");
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