package io.github.thunkware.auto.valhalla.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.AccessFlag;
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

    private static final int ACC_IDENTITY = 0x0020;

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
                List.of(src.toString()),
                List.of("fixture"),
                List.of(),
                28,
                classes.toFile(),
                target.toFile(),
                javacPath,
                processorPath,
                List.of(apiJar));

        assertEquals(2, result.convertedCount(), "Point and Shade both convert");
        assertTrue(result.annotationFailures().isEmpty());
        assertTrue(result.includesFailures().isEmpty());

        Path versionedPoint = classes.resolve("META-INF/versions/28/fixture/Point.class");
        assertTrue(Files.isRegularFile(versionedPoint));
        assertTrue(Files.isRegularFile(classes.resolve("META-INF/versions/28/fixture/Shade.class")));

        ClassModel model = ClassFile.of().parse(Files.readAllBytes(versionedPoint));
        assertEquals(72, model.majorVersion());
        assertEquals(65535, model.minorVersion());
        assertFalse((model.flags().flagsMask() & ACC_IDENTITY) != 0,
                "identity flag must be cleared on the versioned class");
        assertTrue(model.flags().has(AccessFlag.FINAL), "value class must be final");
    }

    @Test
    void interpretedClassFailureIsAttributedToItsSelectionSource() throws Exception {
        Path src = temp.resolve("src");
        Path classes = temp.resolve("classes");
        Path target = temp.resolve("target");
        Files.createDirectories(src.resolve("fixture"));
        // Point: annotation-selected, compiles. SyncPoint: annotation-selected,
        // rejected by javac (value class cannot declare synchronized methods).
        copyFixture(src.resolve("fixture/Point.java"), "fixture/Point.java");
        copyFixture(src.resolve("fixture/SyncPoint.java"), "fixture/SyncPoint.java");

        AutoValhallaSourceTransformer.Result result = AutoValhallaSourceTransformer.transform(
                List.of(src.toString()),
                List.of("fixture"),
                List.of(),
                28,
                classes.toFile(),
                target.toFile(),
                javacPath,
                processorPath,
                List.of(apiJar));

        assertEquals(1, result.convertedCount(), "only Point converts");
        assertEquals(1, result.annotationFailures().size());
        assertTrue(result.annotationFailures().get(0).contains("fixture.SyncPoint"));
        assertTrue(result.annotationFailures().get(0).toLowerCase().contains("synchronized"),
                "javac diagnostics are captured in the failure");
        assertFalse(Files.exists(classes.resolve("META-INF/versions/28/fixture/SyncPoint.class")),
                "nothing may be written for a rejected class");
    }

    @Test
    void excludesOverrideEverything() throws Exception {
        Path src = temp.resolve("src");
        Path classes = temp.resolve("classes");
        Path target = temp.resolve("target");
        Files.createDirectories(src.resolve("fixture"));
        copyFixture(src.resolve("fixture/Point.java"), "fixture/Point.java");

        AutoValhallaSourceTransformer.Result result = AutoValhallaSourceTransformer.transform(
                List.of(src.toString()),
                List.of("fixture"),
                List.of("fixture.Point"),
                28,
                classes.toFile(),
                target.toFile(),
                javacPath,
                processorPath,
                List.of(apiJar));

        assertEquals(0, result.convertedCount());
        assertFalse(Files.exists(classes.resolve("META-INF/versions")));
    }

    private static void copyFixture(Path destination, String resource) throws Exception {
        // fixtures live in src/test/java (not in test resources), so read them
        // from the project source tree rather than the classpath
        Path sourceFixture = Path.of("src", "test", "java", resource);
        assertTrue(Files.isRegularFile(sourceFixture), resource + " fixture must exist");
        Files.write(destination, Files.readAllBytes(sourceFixture));
    }
}