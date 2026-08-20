package io.github.thunkware.auto.valhalla.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Compile-time transformation driver. It scans the project's source roots for
 * {@code @AutoValhalla}-annotated or {@code includes}-matched top-level types,
 * copies the selected source files into a staging directory with their
 * {@code class}/{@code record} declarations adapted into
 * {@code value class}/{@code value record}, and compiles those adapted sources
 * with the JDK 28 compiler ({@code --release <N> --enable-preview}), writing
 * the resulting value-class files under
 * {@code META-INF/versions/<N>} so {@link MultiReleaseJarMojo} can mark the
 * jar as multi-release.
 *
 * <p>There is no bytecode rewriting anywhere: the JVM's own compiler enforces
 * the value-class rules, and classes that javac rejects surface as per-source
 * failures. Selection mirrors the agent: {@code excludes} are checked first and
 * override everything; a type selected by both the annotation and
 * {@code includes} is treated as annotation-selected only.
 */
public final class AutoValhallaSourceTransformer {

    private AutoValhallaSourceTransformer() {
    }

    /** The outcome of a run: how many types were converted and which selected
     *  types javac (or the selection rules) rejected, grouped by selection
     *  source so the annotation-selected failures can fail the build while the
     *  includes-selected ones are normally skipped. */
    public static final class Result {

        private final List<String> annotationFailures = new ArrayList<>();
        private final List<String> includesFailures = new ArrayList<>();
        private int converted;

        private Result() {
        }

        public int convertedCount() {
            return converted;
        }

        public List<String> annotationFailures() {
            return annotationFailures;
        }

        public List<String> includesFailures() {
            return includesFailures;
        }
    }

    /**
     * Runs the source-level transformation.
     *
     * @param sourceRoots      directories containing the project's sources
     * @param includes         package/class patterns to convert (may be empty)
     * @param excludes         patterns never converted, checked first
     * @param versionDirectory the multi-release version directory (== the
     *                         {@code --release} the value classes target)
     * @param outputDirectory  compiled classes directory; versioned value classes
     *                         are written under {@code META-INF/versions/N}
     * @param buildDirectory   Maven {@code target} directory, for the staging area
     * @param javac            the JDK 28 {@code javac} executable
     * @param compileClasspath the project's compile classpath passed to javac
     * @throws IOException on I/O errors during scanning, staging, or compilation
     */
    public static Result transform(List<String> sourceRoots, List<String> includes,
            List<String> excludes, int versionDirectory, File outputDirectory,
            File buildDirectory, String javac, List<String> compileClasspath)
            throws IOException {
        Result result = new Result();
        File staging = new File(buildDirectory, "auto-valhalla-jdk28");
        deleteRecursively(staging);
        File versionedOut = new File(outputDirectory,
                "META-INF/versions/" + versionDirectory);
        for (String rootPath : sourceRoots) {
            File root = new File(rootPath);
            if (!root.isDirectory()) {
                continue;
            }
            List<File> javaFiles = javaFiles(root);
            for (File file : javaFiles) {
                if (isMetaFile(file)) {
                    continue;
                }
                String relativePackage = relativePackage(root, file);
                String source = new String(Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8);
                List<AutoValhallaSourceRewriter.TypeDeclaration> selected =
                        select(source, relativePackage, includes, excludes, result);
                if (selected.isEmpty()) {
                    continue;
                }
                List<String> adaptNames = new ArrayList<>();
                boolean annotationSelected = false;
                boolean includesSelected = false;
                for (AutoValhallaSourceRewriter.TypeDeclaration declaration : selected) {
                    if (!"class".equals(declaration.kind) && !"record".equals(declaration.kind)) {
                        reject(declaration, relativePackage, result,
                                "only class and record declarations can be turned into value types");
                        continue;
                    }
                    adaptNames.add(declaration.name);
                    if (declaration.annotated) {
                        annotationSelected = true;
                    } else {
                        includesSelected = true;
                    }
                }
                if (adaptNames.isEmpty()) {
                    continue;
                }
                String adapted = AutoValhallaSourceRewriter.adapt(source, adaptNames);
                Path staged = staging.toPath().resolve(relativePath(root, file));
                Files.createDirectories(staged.getParent());
                Files.write(staged, adapted.getBytes(StandardCharsets.UTF_8));

                List<String> command = new ArrayList<>();
                command.add(javac);
                command.add("--release");
                command.add(Integer.toString(versionDirectory));
                command.add("--enable-preview");
                command.add("-encoding");
                command.add("UTF-8");
                command.add("-cp");
                command.add(joinPathSeparator(compileClasspath));
                command.add("-d");
                Files.createDirectories(versionedOut.toPath());
                command.add(versionedOut.getAbsolutePath());
                command.add(staged.toString());
                ProcessResult process = run(command);

                if (process.exit == 0) {
                    result.converted += adaptNames.size();
                } else {
                    for (AutoValhallaSourceRewriter.TypeDeclaration declaration : selected) {
                        if (!"class".equals(declaration.kind) && !"record".equals(declaration.kind)) {
                            continue;
                        }
                        reject(declaration, relativePackage, result,
                                "javac reported:\n" + process.output);
                    }
                }
            }
        }
        return result;
    }

    private static void reject(AutoValhallaSourceRewriter.TypeDeclaration declaration,
            String relativePackage, Result result, String reason) {
        String javaName = qualifiedName(relativePackage, declaration);
        if (declaration.annotated) {
            result.annotationFailures.add(javaName + ": " + reason);
        } else {
            result.includesFailures.add(javaName + ": " + reason);
        }
    }

    private static String qualifiedName(String relativePackage,
            AutoValhallaSourceRewriter.TypeDeclaration declaration) {
        return relativePackage.isEmpty()
                ? declaration.name
                : relativePackage + "." + declaration.name;
    }

    /** Filters the top-level declarations into the selected set, applying the
     *  agent's selection semantics. */
    private static List<AutoValhallaSourceRewriter.TypeDeclaration> select(String source,
            String relativePackage, List<String> includes, List<String> excludes, Result result) {
        List<AutoValhallaSourceRewriter.TypeDeclaration> selected = new ArrayList<>();
        for (AutoValhallaSourceRewriter.TypeDeclaration declaration
                : AutoValhallaSourceRewriter.topLevelTypes(source)) {
            if ("module-info".equals(declaration.name) || "package-info".equals(declaration.name)) {
                continue;
            }
            String internal = relativePackage.isEmpty()
                    ? declaration.name : relativePackage + "/" + declaration.name;
            if (AutoValhallaSourceRewriter.patternMatches(excludes, internal)) {
                continue;
            }
            boolean annotated = declaration.annotated;
            boolean included = AutoValhallaSourceRewriter.patternMatches(includes, internal);
            if (!annotated && !included) {
                continue;
            }
            selected.add(declaration);
        }
        return selected;
    }

    // -- file scanning -------------------------------------------------------

    private static List<File> javaFiles(File root) throws IOException {
        List<File> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root.toPath())) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(p -> files.add(p.toFile()));
        }
        return files;
    }

    /** The leading slashed package of {@code file} relative to {@code root},
     *  e.g. {@code com/example} (or {@code ""} for the default package). */
    private static String relativePackage(File root, File file) {
        String relative = relativePath(root, file);
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }

    private static String relativePath(File root, File file) {
        return root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    }

    private static boolean isMetaFile(File file) {
        String name = file.getName();
        return "module-info.java".equals(name) || "package-info.java".equals(name);
    }

    private static void deleteRecursively(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteRecursively(child);
                } else {
                    child.delete();
                }
            }
        }
        dir.delete();
    }

    private static String joinPathSeparator(List<String> paths) {
        StringBuilder joined = new StringBuilder();
        for (String path : paths) {
            if (joined.length() > 0) {
                joined.append(File.pathSeparatorChar);
            }
            joined.append(path);
        }
        return joined.toString();
    }

    private static ProcessResult run(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
        try {
            return new ProcessResult(process.waitFor(), output.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for javac: " + e.getMessage(), e);
        }
    }

    private static final class ProcessResult {

        private final int exit;
        private final String output;

        private ProcessResult(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }
}