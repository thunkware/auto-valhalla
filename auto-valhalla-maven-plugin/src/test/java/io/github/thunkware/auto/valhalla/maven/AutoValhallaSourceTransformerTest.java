package io.github.thunkware.auto.valhalla.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of the source-level transformation: fixture sources are
 * copied to a temp project, the transformer runs the real JDK 28 compiler on
 * the adapted copies, and the produced versioned classes are checked for
 * value-class semantics.
 */
class AutoValhallaSourceTransformerTest {

    private static String javacPath;
    private static String apiJar;
    private static String processorPath;

    @BeforeAll
    static void locateJdk() throws Exception {
        Assumptions.assumeTrue(AutoValhallaMojo.jdkFeature() >= 28,
                "value-class compilation requires JDK 28+");
        javacPath = new File(System.getProperty("java.home"), "bin/javac").getAbsolutePath();
        apiJar = io.github.thunkware.auto.valhalla.api.AutoValhalla.class
                .getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        processorPath = io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor
                .processorPath();
    }

    @TempDir
    Path temp;

    @Test
    void compilesValueClassesIntoVersionsDirectory() throws Exception {
        Path src = temp.resolve("src");
        Path classes = temp.resolve("classes");
        Path target = temp.resolve("target");
        Files.createDirectories(src.resolve("fixture"));
        copyFixture(src.resolve("fixture/Point.java"), "fixture/Point.java");
        copyFixture(src.resolve("fixture/Shade.java"), "fixture/Shade.java");

        AutoValhallaSourceTransformer.Result result = AutoValhallaSourceTransformer.transform(
                java.util.Collections.singletonList(src.toString()),
                28,
                classes.toFile(),
                target.toFile(),
                javacPath,
                processorPath,
                java.util.Collections.singletonList(apiJar));

        assertEquals(1, result.convertedCount(), "only the annotated Point converts");
        assertTrue(result.annotationFailures().isEmpty());

        Path versionedPoint = classes.resolve("META-INF/versions/28/fixture/Point.class");
        assertTrue(Files.isRegularFile(versionedPoint));
        assertFalse(Files.exists(classes.resolve("META-INF/versions/28/fixture/Shade.class")),
                "an unannotated class must not be converted");

        byte[] bytes = Files.readAllBytes(versionedPoint);
        int minor = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
        int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        assertEquals(72, major);
        assertEquals(65535, minor);

        String javapOut = javap(versionedPoint);
        assertTrue(javapOut.contains("value class fixture.Point") || javapOut.contains("class fixture.Point"));
        assertTrue(javapOut.contains("ACC_FINAL"), "value class must be final");
        assertFalse(javapOut.contains("ACC_SUPER"), "value classes do not have ACC_SUPER");
    }

    @Test
    void rejectedClassIsReportedAsAnnotationFailure() throws Exception {
        Path src = temp.resolve("src");
        Path classes = temp.resolve("classes");
        Path target = temp.resolve("target");
        Files.createDirectories(src.resolve("fixture"));
        // Point: compiles. SyncPoint: rejected by javac (a value class cannot
        // declare synchronized methods).
        copyFixture(src.resolve("fixture/Point.java"), "fixture/Point.java");
        copyFixture(src.resolve("fixture/SyncPoint.java"), "fixture/SyncPoint.java");

        AutoValhallaSourceTransformer.Result result = AutoValhallaSourceTransformer.transform(
                java.util.Collections.singletonList(src.toString()),
                28,
                classes.toFile(),
                target.toFile(),
                javacPath,
                processorPath,
                java.util.Collections.singletonList(apiJar));

        assertEquals(1, result.convertedCount(), "only Point converts");
        assertEquals(1, result.annotationFailures().size());
        assertTrue(result.annotationFailures().get(0).contains("fixture.SyncPoint"));
        assertTrue(result.annotationFailures().get(0).toLowerCase().contains("synchronized"),
                "javac diagnostics are captured in the failure");
        assertFalse(Files.exists(classes.resolve("META-INF/versions/28/fixture/SyncPoint.class")),
                "nothing may be written for a rejected class");
    }

    private static void copyFixture(Path destination, String resource) throws Exception {
        // fixtures live in src/test/java (not in test resources), so read them
        // from the project source tree rather than the classpath
        Path sourceFixture = java.nio.file.Paths.get("src", "test", "java", resource);
        assertTrue(Files.isRegularFile(sourceFixture), resource + " fixture must exist");
        Files.write(destination, Files.readAllBytes(sourceFixture));
    }

    private static String javap(Path file) throws Exception {
        File javap = new File(System.getProperty("java.home"), "bin/javap");
        Path out = Files.createTempFile("javap-out-", ".txt");
        Path err = Files.createTempFile("javap-err-", ".txt");
        try {
            Process process = new ProcessBuilder(
                    javap.getAbsolutePath(), "-v", file.toAbsolutePath().toString())
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();
            int rc = process.waitFor();
            String stdout = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
            String stderr = new String(Files.readAllBytes(err), StandardCharsets.UTF_8);
            assertEquals(0, rc, "javap failed for " + file + ":\n" + stdout + stderr);
            return stdout + stderr;
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }
}