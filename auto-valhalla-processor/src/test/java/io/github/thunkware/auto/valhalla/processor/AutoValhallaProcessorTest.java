package io.github.thunkware.auto.valhalla.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thunkware.auto.valhalla.api.AutoValhalla;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of the processor's {@code javac -proc:only} selection pass:
 * fixture sources are written to a temp project, the processor generates the
 * selected types and emitted sources
 * are checked.
 */
class AutoValhallaProcessorTest {

    private static String javacPath;
    private static String apiJar;
    private static String processorPath;

    @BeforeAll
    static void locateJdk() {
        String configuredHome = System.getenv("JAVA28_HOME");
        File javaHome = configuredHome == null || configuredHome.trim().isEmpty()
                ? new File(System.getProperty("java.home"))
                : new File(configuredHome);
        File javac = new File(javaHome, "bin/javac");
        if (!javac.isFile()) {
            javac = new File(javaHome, "../bin/javac");
        }
        javacPath = javac.getAbsoluteFile().getAbsolutePath();
        apiJar = io.github.thunkware.auto.valhalla.api.AutoValhalla.class
                .getProtectionDomain().getCodeSource().getLocation().getPath();
        processorPath = AutoValhallaProcessor.processorPath();
    }

    @TempDir
    Path temp;

    @Test
    void processorPathIsLoadableLocation() {
        assertNotNull(processorPath, "processorPath must be resolvable from the classpath");
        assertTrue(Files.exists(Paths.get(processorPath)),
                "processor path must exist: " + processorPath);
    }

    @Test
    void generatesAnnotatedSourcesOnly() throws Exception {
        Path src = write("fixture/Point.java", POINT);
        write("fixture/Shade.java", SHADE);
        Path out = temp.resolve("out");
        Path classes = temp.resolve("classes");

        ProcessResult pass = runPass(src, out);

        assertEquals(0, pass.exit, pass.output);
        assertSource(out.resolve("fixture/Point.java"), "public final value class Point");
        assertFalse(Files.exists(out.resolve("fixture/Shade.java")),
                "an unannotated class must not be generated");

        if (jdkFeature() >= 28) {
            ProcessResult compile = runValueCompile(out, classes);
            assertEquals(0, compile.exit, compile.output);
            assertTrue(Files.isRegularFile(classes.resolve("fixture/Point.class")));
            assertFalse(Files.exists(classes.resolve("fixture/Shade.class")),
                    "no class file may be produced for an unannotated class");
        }
    }

    @Test
    void recordBecomesValueRecord() throws Exception {
        Path src = write("fixture/R.java", RECORD);
        Path out = temp.resolve("out");

        ProcessResult pass = runPass(src, out);

        assertEquals(0, pass.exit, pass.output);
        assertSource(out.resolve("fixture/R.java"), "public value record R(int a)");
    }

    @Test
    void severalSelectedTypesInOneFileShareTheGeneratedFile() throws Exception {
        Path src = write("fixture/Pair.java", PAIR);
        Path out = temp.resolve("out");

        ProcessResult pass = runPass(src, out);

        assertEquals(0, pass.exit, pass.output);
        assertSource(out.resolve("fixture/Pair.java"), "public final value class Pair");
        assertSource(out.resolve("fixture/Pair.java"), "final value class Side");
    }

    // -- fixture sources -----------------------------------------------------

    private static final String POINT =
            "package fixture;\n"
                    + "\n"
                    + "import io.github.thunkware.auto.valhalla.api.AutoValhalla;\n"
                    + "\n"
                    + "/** Suitable identity class fixture: final class, final fields. */\n"
                    + "@AutoValhalla\n"
                    + "public final class Point {\n"
                    + "\n"
                    + "    public final int x;\n"
                    + "    public final int y;\n"
                    + "\n"
                    + "    public Point(int x, int y) {\n"
                    + "        this.x = x;\n"
                    + "        this.y = y;\n"
                    + "    }\n"
                    + "\n"
                    + "    @Override\n"
                    + "    public String toString() {\n"
                    + "        return \"Point(\" + x + \", \" + y + \")\";\n"
                    + "    }\n"
                    + "}\n";

    private static final String SHADE =
            "package fixture;\n"
                    + "\n"
                    + "/** Suitable value-class candidate, but not annotated: the processor\n"
                    + " *  selects {@code @AutoValhalla} classes only, so this is never\n"
                    + " *  generated. */\n"
                    + "public final class Shade {\n"
                    + "\n"
                    + "    public final int r;\n"
                    + "    public final int g;\n"
                    + "    public final int b;\n"
                    + "\n"
                    + "    public Shade(int r, int g, int b) {\n"
                    + "        this.r = r;\n"
                    + "        this.g = g;\n"
                    + "        this.b = b;\n"
                    + "    }\n"
                    + "}\n";

    private static final String RECORD =
            "package fixture;\n"
                    + "\n"
                    + "import io.github.thunkware.auto.valhalla.api.AutoValhalla;\n"
                    + "\n"
                    + "/** A record selected by annotation. */\n"
                    + "@AutoValhalla\n"
                    + "public record R(int a) {\n"
                    + "}\n";

    private static final String PAIR =
            "package fixture;\n"
                    + "\n"
                    + "import io.github.thunkware.auto.valhalla.api.AutoValhalla;\n"
                    + "\n"
                    + "/** Two annotated top-level types in one file share one generated copy. */\n"
                    + "@AutoValhalla\n"
                    + "public final class Pair {\n"
                    + "\n"
                    + "    public final int a;\n"
                    + "\n"
                    + "    public Pair(int a) {\n"
                    + "        this.a = a;\n"
                    + "    }\n"
                    + "}\n"
                    + "\n"
                    + "/** Same file, also annotated. */\n"
                    + "@AutoValhalla\n"
                    + "final class Side {\n"
                    + "\n"
                    + "    final int b;\n"
                    + "\n"
                    + "    Side(int b) {\n"
                    + "        this.b = b;\n"
                    + "    }\n"
                    + "}\n";

    // -- helpers --------------------------------------------------------------

    /** Writes {@code content} relative to the temp project's {@code src} root
     *  and returns that root, creating parent directories as needed. */
    private Path write(String relative, String content) throws Exception {
        Path file = temp.resolve("src").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return temp.resolve("src");
    }

    private static void assertSource(Path file, String expected) throws Exception {
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(source.contains(expected),
                "expected source to contain [" + expected + "] but was:\n" + source);
    }

    private static ProcessResult runPass(Path srcRoot, Path out) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javacPath);
        command.add("-proc:only");
        command.add("-processorpath");
        command.add(processorPath);
        command.add("-cp");
        command.add(apiJar);
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-A" + AutoValhallaProcessor.OPT_OUTDIR + "=" + out.toAbsolutePath());
        try (Stream<Path> files = Files.walk(srcRoot)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(p -> command.add(p.toAbsolutePath().toString()));
        }
        return run(command);
    }

    private static ProcessResult runValueCompile(Path out, Path classes) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javacPath);
        command.add("--release");
        command.add("28");
        command.add("--enable-preview");
        command.add("-proc:none");
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-cp");
        command.add(apiJar);
        command.add("-d");
        command.add(classes.toAbsolutePath().toString());
        try (Stream<Path> files = Files.walk(out)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(p -> command.add(p.toAbsolutePath().toString()));
        }
        return run(command);
    }

    private static ProcessResult run(List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        }
        String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
        return new ProcessResult(process.waitFor(), output);
    }

    private static final class ProcessResult {

        private final int exit;
        private final String output;

        private ProcessResult(int exit, String output) {
            this.exit = exit;
            this.output = output;
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
