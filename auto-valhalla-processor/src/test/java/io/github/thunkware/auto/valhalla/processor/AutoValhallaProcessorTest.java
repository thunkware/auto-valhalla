package io.github.thunkware.auto.valhalla.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of the processor's {@code javac -proc:only} selection pass:
 * fixture sources are written to a temp project, the processor generates the
 * selected types, and the emitted sources and {@code selection.txt} manifest
 * are checked.
 */
class AutoValhallaProcessorTest {

    private static String javacPath;
    private static String apiJar;
    private static String processorPath;

    @BeforeAll
    static void locateJdk() {
        javacPath = new File(System.getProperty("java.home"), "bin/javac").getAbsolutePath();
        apiJar = io.github.thunkware.auto.valhalla.api.AutoValhalla.class
                .getProtectionDomain().getCodeSource().getLocation().getPath();
        processorPath = AutoValhallaProcessor.processorPath();
    }

    @TempDir
    Path temp;

    @Test
    void processorPathIsLoadableLocation() {
        assertNotNull(processorPath, "processorPath must be resolvable from the classpath");
        assertTrue(Files.exists(Path.of(processorPath)), "processor path must exist: " + processorPath);
    }

    @Test
    void generatesAnnotatedSourcesOnly() throws Exception {
        Path src = write("fixture/Point.java", POINT);
        write("fixture/Shade.java", SHADE);
        Path out = temp.resolve("out");
        Path classes = temp.resolve("classes");

        ProcessResult pass = runPass(src, out);

        assertEquals(0, pass.exit, pass.output);
        assertEquals("GENERATED fixture.Point fixture/Point.java\n", manifest(out));
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
        assertEquals("GENERATED fixture.R fixture/R.java\n", manifest(out));
        assertSource(out.resolve("fixture/R.java"), "public value record R(int a)");
    }

    @Test
    void severalSelectedTypesInOneFileShareTheGeneratedFile() throws Exception {
        Path src = write("fixture/Pair.java", PAIR);
        Path out = temp.resolve("out");

        ProcessResult pass = runPass(src, out);

        assertEquals(0, pass.exit, pass.output);
        assertEquals("""
                GENERATED fixture.Pair fixture/Pair.java
                GENERATED fixture.Side fixture/Pair.java
                """, manifest(out));
        assertSource(out.resolve("fixture/Pair.java"), "public final value class Pair");
        assertSource(out.resolve("fixture/Pair.java"), "final value class Side");
    }

    // -- fixture sources -----------------------------------------------------

    private static final String POINT = """
            package fixture;

            import io.github.thunkware.auto.valhalla.api.AutoValhalla;

            /** Suitable identity class fixture: final class, final fields. */
            @AutoValhalla
            public final class Point {

                public final int x;
                public final int y;

                public Point(int x, int y) {
                    this.x = x;
                    this.y = y;
                }

                @Override
                public String toString() {
                    return "Point(" + x + ", " + y + ")";
                }
            }
            """;

    private static final String SHADE = """
            package fixture;

            /** Suitable value-class candidate, but not annotated: the processor
             *  selects {@code @AutoValhalla} classes only, so this is never
             *  generated. */
            public final class Shade {

                public final int r;
                public final int g;
                public final int b;

                public Shade(int r, int g, int b) {
                    this.r = r;
                    this.g = g;
                    this.b = b;
                }
            }
            """;

    private static final String RECORD = """
            package fixture;

            import io.github.thunkware.auto.valhalla.api.AutoValhalla;

            /** A record selected by annotation. */
            @AutoValhalla
            public record R(int a) {
            }
            """;

    private static final String PAIR = """
            package fixture;

            import io.github.thunkware.auto.valhalla.api.AutoValhalla;

            /** Two annotated top-level types in one file share one generated copy. */
            @AutoValhalla
            public final class Pair {

                public final int a;

                public Pair(int a) {
                    this.a = a;
                }
            }

            /** Same file, also annotated. */
            @AutoValhalla
            final class Side {

                final int b;

                Side(int b) {
                    this.b = b;
                }
            }
            """;

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

    private static String manifest(Path out) throws Exception {
        Path manifest = out.resolve(AutoValhallaProcessor.SELECTION_FILE);
        return Files.exists(manifest)
                ? new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)
                : "";
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
        try (var files = Files.walk(srcRoot)) {
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
        try (var files = Files.walk(out)) {
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
