package io.github.thunkware.auto.valhalla.maven.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link Javac#resolveExecutable(String, int)}. */
class JavacTest {

    @TempDir
    Path temp;

    @AfterEach
    void clearProperties() {
        System.clearProperty("java28.home");
        System.clearProperty("java29.home");
        System.clearProperty("java100.home");
    }

    @Test
    void explicitOverrideWinsEvenWhenAHomeIsSet() throws Exception {
        System.setProperty("java28.home", provisionJdkLayout("jdk28").toString());
        String override = new File("somewhere", "javac").getPath();

        assertEquals(override, Javac.resolveExecutable("  " + override + " ", 28));
    }

    @Test
    void systemPropertyHomeIsUsedForItsVersion() throws Exception {
        Path home = provisionJdkLayout("jdk28");
        System.setProperty("java28.home", home.toString());

        assertEquals(home.resolve("bin/javac").toFile().getAbsolutePath(),
                Javac.resolveExecutable(null, 100));
    }

    @Test
    void eachVersionLooksAtItsOwnHome() throws Exception {
        Path home28 = provisionJdkLayout("jdk28");
        Path home29 = provisionJdkLayout("jdk29");
        System.setProperty("java28.home", home28.toString());
        System.setProperty("java29.home", home29.toString());

        assertEquals(home28.resolve("bin/javac").toFile().getAbsolutePath(),
                Javac.resolveExecutable(null, 28));
        assertEquals(home29.resolve("bin/javac").toFile().getAbsolutePath(),
                Javac.resolveExecutable(null, 29));
    }

    @Test
    void versionsAboveNinetyNineAreDiscovered() throws Exception {
        Path home = provisionJdkLayout("jdk100");
        System.setProperty("java100.home", home.toString());

        assertEquals(home.resolve("bin/javac").toFile().getAbsolutePath(),
                Javac.resolveExecutable(null, 100));
    }

    @Test
    void configuredHomeWithoutJavacFails() {
        System.setProperty("java100.home", temp.toString());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Javac.resolveExecutable(null, 100));
        assertTrue(e.getMessage().contains("java100.home"), e.getMessage());
    }

    @Test
    void blankHomesFallThroughToTheRunningJvm() {
        System.setProperty("java28.home", "  ");

        String expected = new File(System.getProperty("java.home"), "bin/javac").getAbsolutePath();
        assertEquals(expected, Javac.resolveExecutable(null, 28));
    }

    /** Creates a minimal JDK layout ({@code bin/javac}) under the temp dir and
     *  returns its home directory. */
    private Path provisionJdkLayout(String name) throws Exception {
        Path home = temp.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin/javac"));
        return home;
    }
}
