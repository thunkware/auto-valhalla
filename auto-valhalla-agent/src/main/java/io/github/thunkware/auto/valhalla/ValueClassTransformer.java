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
 *       (default {@code ignore-non-final,ignore-synchronized}).</li>
 *   <li>{@code auto-valhalla.includes-mode} — modes for included classes
 *       (default {@code yolo} = {@code ignore-non-final,ignore-synchronized,
 *       mark-fields-final}).</li>
 *   <li>{@code auto-valhalla.debug=true} — verbose logging of decisions.</li>
 *   <li>{@code auto-valhalla.on-fail-throw=true} — surface a loud
 *       {@link java.lang.LinkageError} if a selected class cannot be safely
 *       transformed instead of silently leaving it an identity class.</li>
 *   <li>{@code auto-valhalla.on-fail-append-to=file} — append the internal name of
 *       each selected class that fails to transform to the given file.</li>
 * </ul>
 *
 * <p>By default, classes that fail verification after rewriting are left
 * untouched, so an unsupported class simply keeps identity semantics instead of
 * failing to load. With {@code auto-valhalla.on-fail-throw=true} a
 * {@link java.lang.LinkageError} is thrown instead, surfacing the problem immediately.
 */
public final class ValueClassTransformer implements ClassFileTransformer {

    private final Set<String> includes;
    private final Set<String> excludes;
    private final Set<Mode> annotationMode;
    private final Set<Mode> includesMode;
    private final boolean debug;
    private final boolean onFailThrow;
    private final String onFailAppendTo;
    /** Internal names of classes we turned from non-final into final value
     *  classes, so a later subclass load can be reported by superclass name. */
    private final Set<String> transformedToFinal = ConcurrentHashMap.newKeySet();

    ValueClassTransformer(Set<String> includes, Set<String> excludes,
            Set<Mode> annotationMode, Set<Mode> includesMode,
            boolean debug, boolean onFailThrow, String onFailAppendTo) {
        this.includes = includes;
        this.excludes = excludes;
        this.annotationMode = annotationMode;
        this.includesMode = includesMode;
        this.debug = debug;
        this.onFailThrow = onFailThrow;
        this.onFailAppendTo = onFailAppendTo;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        if (classBeingRedefined != null) {
            // Retransformation is not supported: changing class modifiers
            // (ACC_IDENTITY / ACC_FINAL) is not a legal redefinition.
            return null;
        }
        String internal = className.replace('.', '/');
        if (isExcluded(internal)) {
            return null;
        }
        try {
            ClassModel model = ClassFile.of().parse(classfileBuffer);
            // If a superclass was rewritten into a final value class, this class
            // cannot be loaded; report the offending superclass by name rather
            // than letting the JVM surface a raw IncompatibleClassChangeError.
            String sup = model.superclass().map(ClassEntry::asInternalName).orElse(null);
            if (sup != null && transformedToFinal.contains(sup)) {
                throw new LinkageError("auto-valhalla: class " + internal
                        + " cannot be loaded: it extends " + sup
                        + " which was rewritten into a final value class");
            }
            boolean annotated = ValueClassRewriter.hasAutoValhallaAnnotation(model);
            boolean excluded = patternMatches(excludes, internal);
            if (excluded) {
                return null;
            }
            boolean included = patternMatches(includes, internal);
            // Selection: the annotation and includes choose which classes are
            // candidates. annotation-mode narrows annotation-selected classes,
            // includes-mode narrows includes-selected classes.
            if (!annotated && !included) {
                return null;
            }
            EnumSet<Mode> effective = EnumSet.noneOf(Mode.class);
            if (annotated) {
                effective.addAll(annotationMode);
            }
            if (included) {
                effective.addAll(includesMode);
            }
            boolean ignoreNonFinal = effective.contains(Mode.IGNORE_NON_FINAL);
            boolean ignoreSync = effective.contains(Mode.IGNORE_SYNCHRONIZED);
            if (!ValueClassRewriter.isSuitable(model, ignoreSync, ignoreNonFinal)) {
                return onFail(internal,
                        "is selected for value-class transformation but is not suitable"
                        + " (must extend java.lang.Object or java.lang.Record directly,"
                        + " not be an enum/interface/annotation/module/abstract, must be"
                        + " final unless mode=ignore-non-final, and must not have a"
                        + " synchronized instance method unless mode=ignore-synchronized)");
            }
            if (effective.contains(Mode.MARK_FIELDS_FINAL)
                    && !ValueClassRewriter.fieldsSafeToMarkFinal(model)) {
                return onFail(internal,
                        "is selected for value-class transformation but has a non-final field"
                        + " not written in every constructor (mode=mark-fields-final)");
            }
            byte[] out = ValueClassRewriter.transform(model, onFailThrow, ignoreSync, ignoreNonFinal);
            if (out == null) {
                return onFail(internal,
                        "is selected for value-class transformation but could not be safely"
                        + " transformed");
            }
            // Record classes we turned from non-final into final so that a later
            // subclass load can be reported by superclass name.
            if (!model.flags().has(AccessFlag.FINAL)) {
                transformedToFinal.add(internal);
            }
            if (debug) {
                System.err.println("[auto-valhalla] " + internal + ": transformed to value class ("
                        + out.length + " bytes)");
            }
            return out;
        } catch (Throwable t) {
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
            if (debug) {
                System.err.println("[auto-valhalla] " + internal + ": transform failed:");
                t.printStackTrace(System.err);
            }
            return null;
        }
    }

    /** Handles a selected-but-untransformable class: optionally records the name
     *  and either surfaces a loud failure or leaves the class as an identity
     *  class. */
    private byte[] onFail(String internal, String reason) {
        appendOnFail(internal);
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

    private void appendOnFail(String internal) {
        if (onFailAppendTo == null) {
            return;
        }
        try {
            Files.writeString(Path.of(onFailAppendTo), internal + "\n",
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
     *  {@code on-fail-throw} is set and a selected class cannot be transformed. */
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
