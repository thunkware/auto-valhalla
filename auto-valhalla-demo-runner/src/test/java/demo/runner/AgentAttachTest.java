package demo.runner;

import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.sun.tools.attach.VirtualMachine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the agent's dynamic-attach path ({@code agentmain}): after the agent
 * is attached to the running JVM, a class that is loaded afterwards and matches
 * the configured {@code includes} is rewritten into a value class.
 *
 * <p>Requires the agent jar to have been built first (e.g. via {@code ./build.sh}
 * or {@code mvn package}); the test is skipped otherwise.
 */
public class AgentAttachTest {

    private static final String CANDIDATE_SOURCE =
            "package testattach;\n"
            + "public class Candidate {\n"
            + "    private final int v;\n"
            + "    public Candidate(int v) { this.v = v; }\n"
            + "    public int v() { return v; }\n"
            + "}\n";

    @Test
    void attachTransformsSubsequentlyLoadedClass() throws Exception {
        Path agentTarget = Path.of("..", "auto-valhalla-agent", "target");
        Path jar = null;
        if (Files.isDirectory(agentTarget)) {
            try (var stream = Files.newDirectoryStream(agentTarget, "auto-valhalla-agent-*.jar")) {
                for (var p : stream) {
                    String name = p.getFileName().toString();
                    if (!name.endsWith("-javadoc.jar") && !name.endsWith("-sources.jar")) {
                        jar = p;
                        break;
                    }
                }
            }
        }
        if (jar == null) {
            String prop = System.getProperty("autoValhallaJar");
            jar = (prop != null) ? Path.of(prop)
                                 : agentTarget.resolve("auto-valhalla-agent.jar");
        }
        Assumptions.assumeTrue(Files.exists(jar),
                "agent jar not built; run mvn package first (" + jar + ")");

        Path out = Files.createTempDirectory("auto-valhalla-attach");
        assertTrue(compileCandidate(out), "failed to compile the candidate class");

        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(jar.toString(), "auto-valhalla.includes=testattach.");
        } finally {
            vm.detach();
        }

        // Load Candidate AFTER attach, so the now-active transformer rewrites it.
        try (URLClassLoader cl = new URLClassLoader(
                new URL[] { out.toUri().toURL() }, getClass().getClassLoader())) {
            Class<?> candidate = Class.forName("testattach.Candidate", true, cl);
            Object instance = candidate.getConstructor(int.class).newInstance(7);
            assertFalse(Objects.hasIdentity(instance),
                    "class loaded after attach should have been rewritten to a value class");
        }
    }

    private boolean compileCandidate(Path outDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return false;
        }
        Path src = outDir.resolve("Candidate.java");
        try (Writer w = Files.newBufferedWriter(src)) {
            w.write(CANDIDATE_SOURCE);
        } catch (Exception e) {
            return false;
        }
        int rc = compiler.run(null, null, null, "-d", outDir.toString(), src.toString());
        return rc == 0;
    }
}
