package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(output.contains("MethodParameters"),
                "nested parameters=true must be applied:\n" + output);
        assertFalse(output.contains("LineNumberTable"),
                "nested debug=false must be applied:\n" + output);
    }

    private static String javap(String relativePath) throws Exception {
        Path file = Paths.get(relativePath);
        assertTrue(Files.isRegularFile(file), relativePath + " must exist");
        Process process = new ProcessBuilder(
                JAVAP.getAbsolutePath(), "-v", file.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        int rc = process.waitFor();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = process.getInputStream().read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
        }
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
