package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NestedCompilerConfigurationTest {

    private static final File JAVAP =
            new File(System.getProperty("java.home"), "bin/javap");

    @Test
    void nestedCompilerConfigurationAppliesToGeneratedSources() throws Exception {
        Assumptions.assumeTrue(jdkFeature() >= 28,
                "value-class compilation requires JDK 28+");

        String output = javap("target/classes/META-INF/versions/28/demo/Point.class");
        // parameters=true is honored by maven-compiler-plugin and shows up as -parameters,
        // which javac records as MethodParameters.
        assertTrue(output.contains("MethodParameters"),
                "nested parameters=true must be applied:\n" + output);
        // debug=false is passed through opaquely to maven-compiler-plugin, exactly
        // like the consuming project's own compile. maven-compiler-plugin 3.15 does
        // not turn debug=false into -g:none (plexus-compiler-javac regression), so the
        // compiled value class keeps debug info, matching the base classes. The plugin
        // does not re-translate compiler settings.
        assertTrue(output.contains("LineNumberTable"),
                "value class keeps debug info like base classes (opaque pass-through):\n" + output);
    }

    private static String javap(String relativePath) throws Exception {
        Path file = Paths.get(relativePath);
        assertTrue(Files.isRegularFile(file), relativePath + " must exist");
        Process process = new ProcessBuilder(
                JAVAP.getAbsolutePath(), "-v", file.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        // Drain the process output to EOF before waitFor(): javap -v on a
        // large class file can fill the pipe buffer, and waiting on the process
        // before reading its stream would deadlock.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = process.getInputStream().read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
        }
        int rc = process.waitFor();
        String output = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(0, rc, "javap failed:\n" + output);
        return output;
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
