package io.github.thunkware.auto.valhalla.processor;

import static javax.tools.Diagnostic.Kind.ERROR;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

/**
 * The annotation processor that drives the compile-time value-class
 * transformation. The {@code auto-valhalla-maven-plugin} runs it as a
 * {@code javac -proc:only} pass over the project's sources; for every top-level
 * {@code class}/{@code record} annotated with {@code @AutoValhalla}, it emits an
 * generated copy of the source file in which the declaration keyword becomes
 * {@code value class}/{@code value record}.
 *
 * <p>Generated sources are written under the directory given by the
 * {@code -Aoutdir} option, preserving
 * each file's package-relative layout so the plugin can compile them with
 * {@code javac --release <N> --enable-preview -proc:none} to produce the
 * versioned value-class files.
 *
 */
public class AutoValhallaProcessor extends AbstractProcessor {

    /**
     * The {@code -A} option for output directory
     */
    public static final String OPT_OUTDIR = "outdir";

    /**
     * The {@code -A} option for removing {@code @AutoValhalla} annotation from generated source.
     */
    public static final String OPT_REMOVE_ANNOTATION = "removeAnnotation";

    private static final String ANNOTATION = "io.github.thunkware.auto.valhalla.api.AutoValhalla";
    private static final String ANNOTATION_SIMPLE = "AutoValhalla";

    /**
     * The absolute path of the location this class was loaded from (the jar or
     * class directory to hand to {@code javac -processorpath}), or {@code null}
     * when it cannot be determined. The maven plugin passes this path through
     * to its {@code javac -proc:only} selection pass.
     */
    public static String processorPath() {
        if (AutoValhallaProcessor.class.getProtectionDomain() == null
                || AutoValhallaProcessor.class.getProtectionDomain().getCodeSource() == null) {
            return null;
        }
        try {
            URI uri = AutoValhallaProcessor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            return Paths.get(uri).toFile().getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton("*");
    }

    @Override
    public Set<String> getSupportedOptions() {
        return new HashSet<>(Arrays.asList(OPT_OUTDIR, OPT_REMOVE_ANNOTATION));
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
        Messager messager = processingEnv.getMessager();
        if (outdir == null || outdir.trim().isEmpty()) {
            messager.printMessage(ERROR, "auto-valhalla processor requires the -A" + OPT_OUTDIR + " option");
            return false;
        }
        Path out = Paths.get(outdir.trim());
        boolean removeAnnotation = isOptionEnabled(processingEnv.getOptions().get(OPT_REMOVE_ANNOTATION));

        Trees trees = Trees.instance(processingEnv);
        Map<CompilationUnitTree, List<Selected>> byUnit = new LinkedHashMap<>();
        for (Element root : roots) {
            TypeElement type = topLevelType(root);
            if (type == null) {
                continue;
            }
            if (!isAnnotated(type)) {
                continue;
            }
            String pkg = processingEnv.getElementUtils().getPackageOf(type)
                    .getQualifiedName().toString();
            String qname = type.getQualifiedName().toString();

            TreePath path = trees.getPath(type);
            if (path == null || !(path.getLeaf() instanceof ClassTree)) {
                messager.printMessage(ERROR, "auto-valhalla: " + qname + ": no tree path");
                continue;
            }
            Selected selected = new Selected(path.getCompilationUnit(), pkg, qname, (ClassTree) path.getLeaf());
            byUnit.computeIfAbsent(path.getCompilationUnit(), k -> new ArrayList<>())
                    .add(selected);
        }
        for (List<Selected> unit : byUnit.values()) {
            generateUnit(trees, unit, out, removeAnnotation);
        }
        return false;
    }

    /**
     * Generates every selected type of one compilation selectedUnits into a single generated
     * copy: all {@code value} keywords are inserted before the file is written,
     * so several selected types in one file are all generated. When
     * {@code removeAnnotation} is set the {@code @AutoValhalla} annotations are
     * stripped from the copy as well, so the generated value classes no longer
     * carry the in-source opt-in marker.
     */
    private void generateUnit(Trees trees, List<Selected> selectedUnits, Path out, boolean removeAnnotation) {
        String source = insertValueKeyword(trees, selectedUnits);
        if (source == null) {
            return;
        }
        if (removeAnnotation) {
            source = removeAnnotation(source, trees, selectedUnits);
        }

        Selected selected = selectedUnits.get(0);
        CompilationUnitTree compilationUnit = selected.unit;
        String fileName = fileName(compilationUnit);
        String relDir = selected.pkg.isEmpty() ? "" : selected.pkg.replace('.', '/') + "/";
        String fqClassFileName = relDir + fileName;
        try {
            Path target = out.resolve(fqClassFileName);
            Files.createDirectories(target.getParent());
            Files.write(target, source.getBytes(StandardCharsets.UTF_8));
            processingEnv.getMessager().printMessage(Kind.NOTE, "Writing " + target);
        } catch (IOException e) {
            for (Selected selectedUnit : selectedUnits) {
                failIo(selectedUnit, "cannot write " + fqClassFileName + ": " + e);
            }
        }
    }

    /**
     * Removes the {@code @AutoValhalla} annotations from the generated copy by
     * deleting the exact source ranges of the annotation trees on the selected
     * classes, so arguments (e.g. {@code @AutoValhalla()}) disappear with the
     * annotation instead of leaving a stray {@code ()}. The import is removed
     * only when the simple name is no longer referenced anywhere else.
     */
    private String removeAnnotation(String source, Trees trees, List<Selected> selectedUnits) {
        List<int[]> ranges = new ArrayList<>();
        for (Selected selected : selectedUnits) {
            for (AnnotationTree annotation : selected.classTree.getModifiers().getAnnotations()) {
                if (!isAutoValhalla(annotation)) {
                    continue;
                }
                long start = trees.getSourcePositions().getStartPosition(selected.unit, annotation);
                long end = trees.getSourcePositions().getEndPosition(selected.unit, annotation);
                if (start >= 0 && end >= 0 && start < end && end <= source.length()) {
                    ranges.add(new int[] {(int) start, (int) end});
                }
            }
        }
        ranges.sort((a, b) -> Integer.compare(b[0], a[0]));
        StringBuilder generated = new StringBuilder(source);
        for (int[] range : ranges) {
            generated.delete(range[0], range[1]);
        }
        String result = generated.toString();
        String withoutImport = result.replaceAll(
                "(?m)^[ \\t]*import io\\.github\\.thunkware\\.auto\\.valhalla\\.api\\.AutoValhalla;[ \\t]*(\\r?\\n|$)",
                "");
        if (withoutImport.matches("(?s).*\\bAutoValhalla\\b.*")) {
            return result;
        }
        return withoutImport;
    }

    private static boolean isAutoValhalla(AnnotationTree annotation) {
        Tree type = annotation.getAnnotationType();
        if (type == null) {
            return false;
        }
        String name = type.toString();
        if (name.contains(".")) {
            return ANNOTATION.equals(name);
        }
        return ANNOTATION_SIMPLE.equals(name);
    }

    private String insertValueKeyword(Trees trees, List<Selected> unit) {
        CompilationUnitTree compilationUnit = unit.get(0).unit;
        SourcePositions positions = trees.getSourcePositions();
        String source;
        try {
            source = compilationUnit.getSourceFile().getCharContent(true).toString();
        } catch (IOException e) {
            for (Selected selected : unit) {
                failIo(selected, "cannot read source: " + e);
            }
            return null;
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
        StringBuilder generated = new StringBuilder(source);
        for (int insertIndex : insertIndexes) {
            generated.insert(insertIndex, "value ");
        }
        return generated.toString();
    }

    /**
     * Records an I/O failure for a selected type in the manifest and raises a
     * javac error so the {@code -proc:only} pass fails the build (an I/O
     * problem is a tooling error, not a per-type rejection).
     */
    private void failIo(Selected selected, String reason) {
        processingEnv.getMessager().printMessage(ERROR,
                "auto-valhalla processor: " + selected.qname + ": " + reason);
    }

    /**
     * A selected top-level type plus the context needed to generate its file.
     */
    private static final class Selected {

        private final CompilationUnitTree unit;
        private final String pkg;
        private final String qname;
        private final ClassTree classTree;

        private Selected(CompilationUnitTree unit, String pkg, String qname, ClassTree classTree) {
            this.unit = unit;
            this.pkg = pkg;
            this.qname = qname;
            this.classTree = classTree;
        }

        @Override
        public String toString() {
            return "Selected[" + pkg + ":" + qname + "]";
        }
    }

    /**
     * Returns {@code root} when it is a top-level {@code class}/{@code record},
     * otherwise {@code null} (interfaces, enums, and module/package info are
     * skipped, mirroring the agent's selection).
     */
    private static TypeElement topLevelType(Element root) {
        if (!(root instanceof TypeElement)) {
            return null;
        }
        TypeElement type = (TypeElement) root;
        ElementKind kind = type.getKind();
        if (kind != ElementKind.CLASS && !kind.name().equals("RECORD")) {
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

    /**
     * True when the {@code @AutoValhalla} annotation is attached, matched by
     * fully qualified or simple name.
     */
    private static boolean isAnnotated(TypeElement type) {
        return type.getAnnotationMirrors().stream().anyMatch(mirror -> {
            String name = mirror.getAnnotationType().toString();
            String simple = name.indexOf('.') < 0 ? name : name.substring(name.lastIndexOf('.') + 1);
            return ANNOTATION.equals(name) || ANNOTATION_SIMPLE.equals(simple);
        });
    }

    /**
     * True when an {@code -A} option value enables its feature: present and not
     * an explicit {@code false}.
     */
    private static boolean isOptionEnabled(String value) {
        return value != null && !value.trim().equalsIgnoreCase("false");
    }

    private static String fileName(CompilationUnitTree unit) {
        String name = unit.getSourceFile().getName();
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
