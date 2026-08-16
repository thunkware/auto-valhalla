package demo.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the demo runner's system-property attach path: when started with
 * {@code -Dauto-valhalla.attach=true} (instead of {@code -javaagent}), {@link
 * Main} self-attaches the agent and the demo classes must become value classes.
 *
 * <p>Runs the real {@code demo.runner.Main} in a forked JVM, so it needs the
 * demo jars and the agent jar with ByteBuddyAgent on the classpath (via {@code
 * java.class.path}). Requires the reactor to be built first ({@code ./build.sh}
 * or {@code mvn package}); skipped otherwise.
 */
public class AttachViaSystemPropertyTest {

    @Test
    void attachViaSystemPropertyTransformsDemoClasses() throws Exception {
        Assumptions.assumeTrue(Files.exists(Path.of("target", "classes", "demo", "runner", "Main.class")),
                "demo runner not built; run mvn package first");
        Assumptions.assumeTrue(Files.exists(Path.of("..", "auto-valhalla-agent", "target",
                "auto-valhalla-agent-0.1.0-SNAPSHOT.jar")),
                "agent jar not built; run mvn package first");

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                java,
                "--enable-preview",
                "-Dauto-valhalla.attach=true",
                "-Dauto-valhalla.expect=value",
                "-Dauto-valhalla.includes=demo16.includes.,demo5.includes.",
                "-Dauto-valhalla.annotation-mode=yolo",
                "-Dauto-valhalla.includes-mode=yolo",
                "-cp",
                System.getProperty("java.class.path"),
                "demo.runner.Main");
        Process proc = pb.start();
        String out = readAll(proc.getInputStream()) + readAll(proc.getErrorStream());
        int rc = proc.waitFor();
        System.out.println(out);
        assertTrue(rc == 0, "demo runner exited non-zero: " + rc);
        assertTrue(out.contains("OK: all demo classes behave as value classes"),
                "system-property attach should produce value classes");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}