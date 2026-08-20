package io.github.thunkware.auto.valhalla.processor;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * The annotation processor that drives the compile-time value-class
 * transformation. The {@code auto-valhalla-maven-plugin} runs it as a
 * {@code javac -proc:only} pass over the project's sources; for every top-level
 * {@code class}/{@code record} that is either annotated with
 * {@code @AutoValhalla} or matches an {@code includes} pattern (and is not
 * excluded), it emits an adapted copy of the source file in which the
 * declaration keyword becomes {@code value class}/{@code value record}.
 *
 * <p>Adapted sources and a selection manifest ({@code selection.txt}) are
 * written under the directory given by the {@code -Aoutdir} option, preserving
 * each file's package-relative layout so the plugin can compile them with
 * {@code javac --release <N> --enable-preview -proc:none} to produce the
 * versioned value-class files.
 *
 * <p>Selection mirrors the agent: {@code excludes} are checked first and
 * override everything; a type selected by both the annotation and
 * {@code includes} is treated as annotation-selected only. A manifest line is
 * one of:
 * <ul>
 *   <li>{@code ADAPTED annotated <qname> <relPath>}</li>
 *   <li>{@code ADAPTED includes <qname> <relPath>}</li>
 *   <li>{@code FAIL annotated|includes <qname> <reason>}</li>
 * </ul>
 * The plugin reads this manifest to report and bucket failures.
 */
public class AutoValhallaProcessor extends AbstractProcessor {

    /** The {@code -A} option with the class/package patterns to convert. */
    public static final String OPT_INCLUDES = "includes";

    /** The {@code -A} option with the patterns never converted, checked first. */
    public static final String OPT_EXCLUDES = "excludes";

    /** The {@code -A} option with the directory that receives the adapted
     *  sources and the {@code selection.txt} manifest. */
    public static final String OPT_OUTDIR = "outdir";

    /** The absolute path of the location this class was loaded from (the jar or
     *  class directory to hand to {@code javac -processorpath}), or {@code null}
     *  when it cannot be determined. The maven plugin passes this path through
     *  to its {@code javac -proc:only} selection pass. */
    public static String processorPath() {
        if (AutoValhallaProcessor.class.getProtectionDomain() == null
                || AutoValhallaProcessor.class.getProtectionDomain().getCodeSource() == null) {
            return null;
        }
        try {
            return Path.of(AutoValhallaProcessor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toFile().getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Name of the selection manifest written into the out directory. */
    public static final String SELECTION_FILE = "selection.txt";

    private static final String ANNOTATION = "io.github.thunkware.auto.valhalla.api.AutoValhalla";
    private static final String ANNOTATION_SIMPLE = "AutoValhalla";

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(OPT_INCLUDES, OPT_EXCLUDES, OPT_OUTDIR);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        Set<? extends Element> roots = roundEnv.getRootElements();
        if (roots.isEmpty()) {
            return false;
        }
        String outdir = processingEnv.getOptions().get(OPT_OUTDIR);
        if (outdir == null || outdir.trim().isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "auto-valhalla processor requires the -A" + OPT_OUTDIR + " option");
            return false;
        }
        List<String> includes = split(processingEnv.getOptions().getOrDefault(OPT_INCLUDES, ""));
        List<String> excludes = split(processingEnv.getOptions().getOrDefault(OPT_EXCLUDES, ""));
        List<String> report = new ArrayList<>();
        Path out = Path.of(outdir.trim());

        Trees trees = Trees.instance(processingEnv);
        Map<CompilationUnitTree, List<Selected>> byUnit = new LinkedHashMap<>();
        for (Element root : roots) {
            TypeElement type = topLevelType(root);
            if (type == null) {
                continue;
            }
            String pkg = processingEnv.getElementUtils().getPackageOf(type)
                    .getQualifiedName().toString();
            String internal = internalName(pkg, type);
            if (patternMatches(excludes, internal)) {
                continue;
            }
            boolean annotated = isAnnotated(type);
            boolean included = patternMatches(includes, internal);
            if (!annotated && !included) {
                continue;
            }
            String bucket = annotated ? "annotated" : "includes";
            String qname = type.getQualifiedName().toString();

            TreePath path = trees.getPath(type);
            if (path == null || !(path.getLeaf() instanceof ClassTree)) {
                report.add("FAIL " + bucket + " " + qname + " no tree path");
                continue;
            }
            byUnit.computeIfAbsent(path.getCompilationUnit(), k -> new ArrayList<>())
                    .add(new Selected(path.getCompilationUnit(), pkg, bucket, qname,
                            (ClassTree) path.getLeaf()));
        }
        for (List<Selected> unit : byUnit.values()) {
            adaptUnit(trees, unit, out, report);
        }
        try {
            Files.createDirectories(out);
            String content = report.isEmpty() ? "" : String.join("\n", report) + "\n";
            Files.write(out.resolve(SELECTION_FILE), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "auto-valhalla processor cannot write " + SELECTION_FILE + ": " + e);
        }
        return false;
    }

    /** Adapts every selected type of one compilation unit into a single staged
     *  copy: all {@code value} keywords are inserted before the file is written,
     *  so several selected types in one file (e.g. an annotated class next to an
     *  includes-matched one) are all adapted and one {@code ADAPTED} line is
     *  recorded per type. */
    private void adaptUnit(Trees trees, List<Selected> unit, Path out, List<String> report) {
        CompilationUnitTree compilationUnit = unit.get(0).unit;
        SourcePositions positions = trees.getSourcePositions();
        String source;
        try {
            source = compilationUnit.getSourceFile().getCharContent(true).toString();
        } catch (IOException e) {
            for (Selected selected : unit) {
                report.add("FAIL " + selected.bucket + " " + selected.qname
                        + " cannot read source: " + e);
            }
            return;
        }
        List<Integer> insertIndexes = new ArrayList<>();
        for (Selected selected : unit) {
            int insertIndex = (int) positions.getEndPosition(
                    compilationUnit, selected.classTree.getModifiers());
            if (insertIndex < 0 || insertIndex > source.length()) {
                insertIndex = 0;
            }
            while (insertIndex < source.length()
                    && Character.isWhitespace(source.charAt(insertIndex))) {
                insertIndex++;
            }
            insertIndexes.add(insertIndex);
        }
        insertIndexes.sort((a, b) -> Integer.compare(b, a));
        StringBuilder adapted = new StringBuilder(source);
        for (int insertIndex : insertIndexes) {
            adapted.insert(insertIndex, "value ");
        }
        String fileName = fileName(compilationUnit);
        Selected first = unit.get(0);
        String relDir = first.pkg.isEmpty() ? "" : first.pkg.replace('.', '/') + "/";
        String rel = relDir + fileName;
        try {
            Path target = out.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.write(target, adapted.toString().getBytes(StandardCharsets.UTF_8));
            for (Selected selected : unit) {
                report.add("ADAPTED " + selected.bucket + " " + selected.qname + " " + rel);
            }
        } catch (IOException e) {
            for (Selected selected : unit) {
                report.add("FAIL " + selected.bucket + " " + selected.qname
                        + " cannot write " + rel + ": " + e);
            }
        }
    }

    /** A selected top-level type plus the context needed to adapt its file. */
    private static final class Selected {

        private final CompilationUnitTree unit;
        private final String pkg;
        private final String bucket;
        private final String qname;
        private final ClassTree classTree;

        private Selected(CompilationUnitTree unit, String pkg, String bucket, String qname,
                ClassTree classTree) {
            this.unit = unit;
            this.pkg = pkg;
            this.bucket = bucket;
            this.qname = qname;
            this.classTree = classTree;
        }
    }

    /** Returns {@code root} when it is a top-level {@code class}/{@code record},
     *  otherwise {@code null} (interfaces, enums, and module/package info are
     *  skipped, mirroring the agent's selection). */
    private static TypeElement topLevelType(Element root) {
        if (!(root instanceof TypeElement)) {
            return null;
        }
        TypeElement type = (TypeElement) root;
        ElementKind kind = type.getKind();
        if (kind != ElementKind.CLASS && kind != ElementKind.RECORD) {
            return null;
        }
        if (type.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
            return null;
        }
        String name = type.getSimpleName().toString();
        if ("module-info".equals(name) || "package-info".equals(name)) {
            return null;
        }
        return type;
    }

    /** Slashed internal name, e.g. {@code com/example/Point} (no leading
     *  slash; just the simple name for the default package). */
    private static String internalName(String pkg, TypeElement type) {
        return pkg.isEmpty()
                ? type.getSimpleName().toString()
                : pkg.replace('.', '/') + "/" + type.getSimpleName();
    }

    /** True when the {@code @AutoValhalla} annotation is attached, matched by
     *  fully qualified or simple name. */
    private static boolean isAnnotated(TypeElement type) {
        return type.getAnnotationMirrors().stream().anyMatch(mirror -> {
            String name = mirror.getAnnotationType().toString();
            String simple = name.indexOf('.') < 0 ? name : name.substring(name.lastIndexOf('.') + 1);
            return ANNOTATION.equals(name) || ANNOTATION_SIMPLE.equals(simple);
        });
    }

    /** Mirrors {@code ValueClassTransformer#patternMatches}: {@code *} matches
     *  everything; a pattern ending in {@code /} is a package-prefix match;
     *  otherwise the pattern matches an exact class or an exact or recursive
     *  package. Patterns may use dots or slashes. */
    static boolean patternMatches(List<String> patterns, String internal) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        String pkg = internal.indexOf('/') < 0 ? "" : internal.substring(0, internal.lastIndexOf('/'));
        for (String pattern : patterns) {
            String normalized = pattern.replace('.', '/');
            if ("*".equals(normalized)) {
                return true;
            }
            if (normalized.endsWith("/")) {
                if (internal.startsWith(normalized)) {
                    return true;
                }
            } else if (internal.equals(normalized) || pkg.equals(normalized)
                    || pkg.startsWith(normalized + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String fileName(CompilationUnitTree unit) {
        String name = unit.getSourceFile().getName();
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private static List<String> split(String value) {
        if (value == null || value.trim().isEmpty()) {
            return List.of();
        }
        List<String> patterns = new ArrayList<>();
        for (String pattern : value.split(",")) {
            if (!pattern.trim().isEmpty()) {
                patterns.add(pattern.trim());
            }
        }
        return patterns;
    }
}
