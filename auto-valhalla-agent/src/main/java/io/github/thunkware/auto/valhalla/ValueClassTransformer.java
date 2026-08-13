package io.github.thunkware.auto.valhalla;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link ClassFileTransformer} that converts matching classes into JEP 401
 * value classes as they are loaded.
 *
 * <p>A class is a candidate when it is annotated with {@link AutoValhalla} or its
 * name matches one of the configured {@code includes} patterns (which supports
 * {@code *}). Matching classes named by {@code excludes} are never converted.
 *
 * <p>{@code annotation-mode} narrows annotation-selected candidates and
 * {@code includes-mode} narrows includes-selected ones (see {@link Mode}).
 *
 * <p>Selection and behavior are controlled by the following flags, supplied
 * either as agent arguments ({@code -javaagent:auto-valhalla.jar=...}), as
 * system properties, as environment variables, or via a {@code .config}
 * properties file (canonical hyphenated form):
 * <ul>
 *   <li>{@code auto-valhalla.includes} — classes/packages to convert. {@code *}
 *       matches everything; a value ending in {@code .} or {@code .*} is a
 *       package prefix.</li>
 *   <li>{@code auto-valhalla.excludes} — classes/packages to skip (wins over
 *       includes and the annotation).</li>
 *   <li>{@code auto-valhalla.annotation-mode} — modes for annotated classes
 *       (default {@code safe}).</li>
 *   <li>{@code auto-valhalla.includes-mode} — modes for included classes
 *       (default {@code yolo} = {@code mark-class-final,ignore-synchronized,
 *       mark-fields-final}).</li>
 *   <li>{@code auto-valhalla.debug=true} — verbose logging of decisions.</li>
 *   <li>{@code auto-valhalla.annotation.on-fail-throw=true} (default) — surface
 *       a loud {@link java.lang.LinkageError} if an <em>annotation-selected</em>
 *       class cannot be safely transformed instead of silently leaving it an
 *       identity class.</li>
 *   <li>{@code auto-valhalla.includes.on-fail-throw=true} (default false) — the
 *       same, for <em>includes-selected</em> classes (off by default so a broad
 *       includes sweep cannot crash the application).</li>
*   <li>{@code auto-valhalla.annotation.on-fail-append-to=file} and
     *       {@code auto-valhalla.includes.on-fail-append-to=file} — append the
     *       Java dot name of each failing class (e.g. {@code com.example.Foo},
     *       not {@code com/example/Foo}) to the given file, per selection
     *       source.</li>
 * </ul>
 *
 * <p>A class selected by both the annotation and {@code includes} follows the
 * annotation settings (an explicit in-source opt-in is the stronger statement).
 * By default, includes-selected classes that fail verification after rewriting
 * are left untouched, so an unsupported class simply keeps identity semantics
 * instead of failing to load.
 */
public final class ValueClassTransformer implements ClassFileTransformer {

    private final Set<String> includes;
    private final Set<String> excludes;
    private final Set<Mode> annotationMode;
    private final Set<Mode> includesMode;
    private final boolean debug;
    private final boolean annotationOnFailThrow;
    private final String annotationOnFailAppendTo;
    private final boolean includesOnFailThrow;
    private final String includesOnFailAppendTo;
    /** Internal names of classes we turned from non-final into final value
     *  classes, so a later subclass load can be reported by superclass name. */
    private final Set<String> transformedToFinal = ConcurrentHashMap.newKeySet();
    /** Internal names of classes we turned into abstract value classes; such a
     *  superclass is a legal base for both value and identity subclasses, so it
     *  is recorded (not to error on, but so subclasses may themselves become
     *  value classes). */
    private final Set<String> transformedToAbstract = ConcurrentHashMap.newKeySet();

    ValueClassTransformer(Set<String> includes, Set<String> excludes,
            Set<Mode> annotationMode, Set<Mode> includesMode,
            boolean debug,
            boolean annotationOnFailThrow, String annotationOnFailAppendTo,
            boolean includesOnFailThrow, String includesOnFailAppendTo) {
        this.includes = includes;
        this.excludes = excludes;
        this.annotationMode = annotationMode;
        this.includesMode = includesMode;
        this.debug = debug;
        this.annotationOnFailThrow = annotationOnFailThrow;
        this.annotationOnFailAppendTo = annotationOnFailAppendTo;
        this.includesOnFailThrow = includesOnFailThrow;
        this.includesOnFailAppendTo = includesOnFailAppendTo;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (className == null || className.isEmpty() || classBeingRedefined != null) {
            // No name, or a retransform: changing class modifiers
            // (ACC_IDENTITY / ACC_FINAL) is not a legal redefinition.
            return null;
        }
        String internal = className.replace('.', '/');
        if (isExcluded(internal)) {
            return null;
        }
        // Before a class has been classified as annotation- or includes-selected,
        // failures must not take any loud on-fail setting: a class that is not
        // selected at all must not crash the app because it happened to fail to
        // parse. Pre-selection failures (e.g. an unparseable class file) are
        // always treated as "leave as identity"; the selection's settings only
        // apply once selection is known.
        boolean onFailThrow = false;
        String onFailAppendTo = null;
        try {
            ClassModel model = ClassFile.of().parse(classfileBuffer);
            Selection selection = select(internal, model);
            if (selection == null) {
                return null;
            }
            onFailThrow = selection.onFailThrow();
            onFailAppendTo = selection.onFailAppendTo();
            return rewrite(internal, model, selection);
        } catch (LinkageError e) {
            throw e;
        } catch (Throwable t) {
            return onTransformError(internal, t, onFailThrow, onFailAppendTo);
        }
    }

    /**
     * Decides how {@code internal} was selected. Returns {@code null} when it is
     * not a candidate at all; otherwise the effective mode set (the union of the
     * annotation and includes mode sets) together with the failure settings of
     * the selection source that applies — the annotation wins when both select
     * the class.
     *
     * <p>Throws a {@link LinkageError} naming the superclass when that superclass
     * was previously rewritten into a final value class, since this class cannot
     * be loaded at all.
     */
    private Selection select(String internal, ClassModel model) {
        String sup = model.superclass().map(ClassEntry::asInternalName).orElse(null);
        if (sup != null && transformedToFinal.contains(sup)) {
            throw new LinkageError("auto-valhalla: class " + internal
                    + " cannot be loaded: it extends " + sup
                    + " which was rewritten into a final value class");
        }
        if (patternMatches(excludes, internal)) {
            return null;
        }
        boolean annotated = ValueClassRewriter.hasAutoValhallaAnnotation(model);
        boolean included = patternMatches(includes, internal);
        if (!annotated && !included) {
            return null;
        }
        // Failure handling follows the selection source: an annotation is an
        // explicit opt-in by default failing loudly; includes sweep broadly and
        // by default leave classes as identity. When both apply, the annotation
        // wins. Modes from the two sources are combined (the union applies).
        boolean onFailThrow = annotated ? annotationOnFailThrow : includesOnFailThrow;
        String onFailAppendTo = annotated
                ? annotationOnFailAppendTo : includesOnFailAppendTo;
        EnumSet<Mode> effective = EnumSet.noneOf(Mode.class);
        if (annotated) {
            effective.addAll(annotationMode);
        }
        if (included) {
            effective.addAll(includesMode);
        }
        return new Selection(effective, onFailThrow, onFailAppendTo);
    }

    /**
     * Rewrites a selected class: runs the suitability checks, applies the
     * configured modes, and records classes we turned into final value classes so
     * later subclass loads can be reported by superclass name. Failure handling
     * follows the selection's settings.
     */
    private byte[] rewrite(String internal, ClassModel model, Selection selection) {
        Set<Mode> effective = selection.effective();
        boolean ignoreSync = effective.contains(Mode.IGNORE_SYNCHRONIZED);
        boolean markClassFinal = effective.contains(Mode.MARK_CLASS_FINAL);
        List<String> problems = ValueClassRewriter.suitabilityProblems(
                model, ignoreSync, markClassFinal, transformedToAbstract);
        if (!problems.isEmpty()) {
            return onFail(internal, "is selected for value-class transformation but is not"
                    + " suitable: " + String.join("; ", problems),
                    selection.onFailThrow(), selection.onFailAppendTo());
        }
        if (effective.contains(Mode.MARK_FIELDS_FINAL)
                && !ValueClassRewriter.fieldsSafeToMarkFinal(model)) {
            return onFail(internal,
                    "is selected for value-class transformation but has a non-final field"
                    + " not written in every constructor (mode=mark-fields-final)",
                    selection.onFailThrow(), selection.onFailAppendTo());
        }
        byte[] out = ValueClassRewriter.transform(model, selection.onFailThrow(),
                ignoreSync, markClassFinal, transformedToAbstract);
        if (out == null) {
            return onFail(internal,
                    "is selected for value-class transformation but could not be safely"
                    + " transformed", selection.onFailThrow(), selection.onFailAppendTo());
        }
        // Record what we turned classes into so later loads can reason about
        // them: a non-final (non-abstract) class becomes a final value class
        // (its subclasses stop loading), while an abstract class becomes an
        // abstract value class (whose value and identity subclasses remain
        // legal and may themselves be converted).
        if (model.flags().has(AccessFlag.ABSTRACT)) {
            transformedToAbstract.add(internal);
        } else if (!model.flags().has(AccessFlag.FINAL)) {
            transformedToFinal.add(internal);
        }
        if (debug) {
            System.err.println("[auto-valhalla] " + internal + ": transformed to value class ("
                    + out.length + " bytes)");
        }
        return out;
    }

    /**
     * The selection of a loaded class: the effective mode set (the union of the
     * applicable annotation/includes modes) and the failure settings of the
     * selection source that applied.
     */
    private record Selection(Set<Mode> effective,
            boolean onFailThrow, String onFailAppendTo) {}

    /** Handles an unexpected failure: rethrows a {@link LinkageError}, otherwise
     *  surfaces a loud failure or leaves the class as identity per the effective
     *  {@code onFail} settings. */
    private byte[] onTransformError(String internal, Throwable t,
            boolean onFailThrow, String onFailAppendTo) {
        if (t instanceof LinkageError) {
            // e.g. a superclass was rewritten into a final value class: this
            // class cannot be loaded regardless of on-fail-throw, so surface
            // the (superclass-naming) LinkageError rather than swallowing it.
            throw (LinkageError) t;
        }
        if (onFailThrow) {
            throw new LinkageError("auto-valhalla: failed to transform " + internal
                    + " into a value class: " + t, t);
        }
        appendOnFail(internal, onFailAppendTo);
        if (debug) {
            System.err.println("[auto-valhalla] " + internal + ": transform failed:");
            t.printStackTrace(System.err);
        }
        return null;
    }

    /** Handles a selected-but-untransformable class: optionally records the name
     *  and either surfaces a loud failure ({@code onFailThrow}) or leaves the
     *  class as an identity class. {@code onFailThrow} / {@code onFailAppendTo}
     *  come from the class's selection source (annotation vs includes). */
    private byte[] onFail(String internal, String reason,
            boolean onFailThrow, String onFailAppendTo) {
        appendOnFail(internal, onFailAppendTo);
        if (onFailThrow) {
            System.err.println("[auto-valhalla] " + internal + " " + reason
                    + "; the JVM will reject it rather than silently keep an"
                    + " identity class.");
            // A ClassFileTransformer exception would be swallowed by the JVM, so instead
            // hand back a class file that fails to load, surfacing the failure loudly.
            return brokenClass();
        }
        if (debug) {
            System.err.println("[auto-valhalla] " + internal + ": " + reason
                    + ", leaving as identity class");
        }
        return null;
    }

    private void appendOnFail(String internal, String onFailAppendTo) {
        if (onFailAppendTo == null) {
            return;
        }
        // Write Java dot class names (demo5.Shape, not demo5/Shape) so the file
        // can be fed straight back to includes-file / excludes-file.
        try {
            Files.writeString(Path.of(onFailAppendTo), internal.replace('/', '.') + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            if (debug) {
                System.err.println("[auto-valhalla] cannot append to "
                        + onFailAppendTo + ": " + e);
            }
        }
    }

    /**
     * Returns true if {@code internalName} matches any pattern in {@code patterns}.
     * Patterns may use either dots ({@code com.example.Foo}) or slashes
     * ({@code com/example/Foo}); both are normalized to internal-name form.
     * <ul>
     *   <li>{@code *} matches everything;</li>
     *   <li>a pattern ending in {@code .} or {@code /} is a package prefix;</li>
     *   <li>a pattern containing a {@code .} or {@code /} is an exact class name;</li>
     *   <li>a bare word (no separator) matches a package of that name or an exact
     *       class of that name.</li>
     * </ul>
     */
    static boolean patternMatches(Set<String> patterns, String internalName) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String p : patterns) {
            String norm = p.replace('.', '/');
            if (norm.equals("*")) {
                return true;
            }
            if (norm.endsWith("/")) {
                if (internalName.startsWith(norm)) {
                    return true;
                }
            } else if (norm.contains("/")) {
                if (internalName.equals(norm)) {
                    return true;
                }
            } else if (internalName.equals(norm) || internalName.startsWith(norm + "/")) {
                return true;
            }
        }
        return false;
    }

    /** An unloadable class file, used to force a {@link java.lang.ClassFormatError} when
     *  {@code annotation/includes.on-fail-throw} is set and a selected class cannot be
     *  transformed. */
    private static byte[] brokenClass() {
        return new byte[] { 0, 0, 0, 0 };
    }

    private static boolean isExcluded(String internal) {
        if (internal.startsWith("java/")
                || internal.startsWith("javax/")
                || internal.startsWith("sun/")
                || internal.startsWith("jdk/")) {
            return true;
        }
        // never transform the agent's own support classes (which include the
        // embedded @AutoValhalla annotation)
        return internal.startsWith("io/github/thunkware/auto/valhalla/");
    }
}
