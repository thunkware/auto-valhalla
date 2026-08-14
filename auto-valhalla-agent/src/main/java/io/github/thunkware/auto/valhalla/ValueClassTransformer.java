package io.github.thunkware.auto.valhalla;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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
 * For the full list of configuration options see {@link AutoValhallaAgent}.
 *
 * <p>A class selected by both the annotation and {@code includes} is treated as
 * annotation-selected only: its mode set and failure settings come from the
 * annotation (an explicit in-source opt-in is the stronger statement).
 * By default, includes-selected classes that fail verification after rewriting
 * are left untouched, so an unsupported class simply keeps identity semantics
 * instead of failing to load.
 */
public final class ValueClassTransformer implements ClassFileTransformer {

    private final Config config;
    /** Internal names of classes we turned from non-final into final value
     *  classes, so a later subclass load can be reported by superclass name. */
    private final Set<String> transformedToFinal = ConcurrentHashMap.newKeySet();

    ValueClassTransformer(Config cfg) {
        this.config = cfg;
        // Initialize AsyncFileWriter for each append-to path so files are read
        // at startup (deduplicating against existing names). AsyncFileWriter is
        // shared per-path, so success and failure appends to the same file
        // deduplicate against each other.
        Stream.of(cfg.annotationOnFailAppendTo, cfg.annotationOnSuccessAppendTo, cfg.includesOnFailAppendTo,
                        cfg.includesOnSuccessAppendTo, cfg.synchronizationMonitorAppendTo)
                .filter(Objects::nonNull)
                .forEach(AsyncFileWriter::forFile);

        // Configure SynchronizationMonitor with the path so it can record
        // classes being synchronized on.
        if (cfg.synchronizationMonitorAppendTo != null) {
            SynchronizationMonitor.configure(cfg.synchronizationMonitorAppendTo);
        }
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String classNameJvm,
            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (classNameJvm == null || classNameJvm.isEmpty() || classBeingRedefined != null) {
            // No name, or a retransform: changing class modifiers
            // (ACC_IDENTITY / ACC_FINAL) is not a legal redefinition.
            return null;
        }
        ClassName className = ClassName.of(classNameJvm);
        if (isExcluded(className)) {
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
            Selection selection = select(className, model);
            if (selection == null) {
                return null;
            }
            onFailThrow = selection.onFailThrow();
            onFailAppendTo = selection.onFailAppendTo();
            // SYNCHRONIZATION_MONITOR is an either-or mode: either rewrite the class
            // to a value class, or instrument monitorenter calls, but not both.
            if (selection.effective().contains(Mode.SYNCHRONIZATION_MONITOR)) {
                return monitorSynchronization(model, className, selection);
            }
            return rewrite(className, model, selection);
        } catch (LinkageError e) {
            throw e;
        } catch (Throwable t) {
            return onTransformError(className, t, onFailThrow, onFailAppendTo);
        }
    }

    private byte[] monitorSynchronization(ClassModel model, ClassName className, Selection selection) {
        if (!SynchronizationInstrumenter.hasMonitorEnter(model)) {
            return null;
        }
        byte[] monitored = SynchronizationInstrumenter.instrument(model);
        if (monitored != null) {
            InternalLogger.debug(className.java()
                    + ": instrumented for synchronization monitoring");
            return monitored;
        }
        return onFail(className,
                "is selected for synchronization monitoring but could not be"
                        + " instrumented (stack-map verification failed)",
                selection.onFailThrow(), selection.onFailAppendTo());
    }

    /**
     * Decides how {@code internal} was selected. Returns {@code null} when it is
     * not a candidate at all; otherwise the effective mode set together with the
     * failure settings of the selection source that applies. A class selected by
     * both counts as annotation-selected only.
     *
     * <p>Throws a {@link LinkageError} naming the superclass when that superclass
     * was previously rewritten into a final value class, since this class cannot
     * be loaded at all.
     */
    private Selection select(ClassName className, ClassModel model) {
        String sup = model.superclass().map(ClassEntry::asInternalName).orElse(null);
        if (sup != null && transformedToFinal.contains(sup)) {
            throw new LinkageError("auto-valhalla: class " + className.java()
                    + " cannot be loaded: it extends " + sup
                    + " which was rewritten into a final value class");
        }
        if (patternMatches(config.excludes, className)) {
            return null;
        }
        boolean annotated = ValueClassRewriter.hasAutoValhallaAnnotation(model);
        boolean included = patternMatches(config.includes, className);
        if (!annotated && !included) {
            return null;
        }
        // Both selection sources can match the same class; in that case the
        // annotation (an explicit in-source opt-in) is the stronger statement,
        // so the class is treated as annotation-selected only: its mode set and
        // failure settings, with no contribution from includes.
        boolean onFailThrow = config.annotationOnFailThrow;
        String onFailAppendTo = config.annotationOnFailAppendTo;
        String onSuccessAppendTo = config.annotationOnSuccessAppendTo;
        EnumSet<Mode> effective = EnumSet.noneOf(Mode.class);
        if (annotated) {
            effective.addAll(config.annotationMode);
        } else {
            onFailThrow = config.includesOnFailThrow;
            onFailAppendTo = config.includesOnFailAppendTo;
            onSuccessAppendTo = config.includesOnSuccessAppendTo;
            effective.addAll(config.includesMode);
        }
        // SYNCHRONIZATION_MONITOR cannot be used in combination with other modes
        if (effective.contains(Mode.SYNCHRONIZATION_MONITOR) && effective.size() > 1) {
            throw new IllegalArgumentException(
                    "mode=synchronization-monitor cannot be combined with other modes; "
                    + "got: " + effective);
        }
        return new Selection(effective, onFailThrow, onFailAppendTo, onSuccessAppendTo);
    }

    /**
     * Rewrites a selected class: runs the suitability checks, applies the
     * configured modes, and records classes we turned into final value classes so
     * later subclass loads can be reported by superclass name. Failure handling
     * follows the selection's settings.
     */
    private byte[] rewrite(ClassName className, ClassModel model, Selection selection) {
        Set<Mode> effective = selection.effective();
        boolean ignoreSync = effective.contains(Mode.IGNORE_SYNCHRONIZED);
        boolean markClassFinal = effective.contains(Mode.MARK_CLASS_FINAL);
        List<String> problems = ValueClassRewriter.suitabilityProblems(
                model, ignoreSync, markClassFinal);
        if (!problems.isEmpty()) {
            return onFail(className, "is selected for value-class transformation but is not"
                    + " suitable: " + String.join("; ", problems),
                    selection.onFailThrow(), selection.onFailAppendTo());
        }
        if (effective.contains(Mode.MARK_FIELDS_FINAL)
                && !ValueClassRewriter.fieldsSafeToMarkFinal(model)) {
            return onFail(className,
                    "is selected for value-class transformation but has a non-final field"
                    + " not written in every constructor (mode=mark-fields-final)",
                    selection.onFailThrow(), selection.onFailAppendTo());
        }
        byte[] out = ValueClassRewriter.transform(model, selection.onFailThrow(),
                ignoreSync, markClassFinal);
        if (out == null) {
            return onFail(className,
                    "is selected for value-class transformation but could not be safely"
                    + " transformed", selection.onFailThrow(), selection.onFailAppendTo());
        }
        // Record what we turned classes into so later loads can reason about
        // them: a non-final class becomes a final value class (its subclasses
        // stop loading). Abstract classes are never converted.
        if (!model.flags().has(AccessFlag.FINAL)) {
            transformedToFinal.add(className.jvm());
        }
        appendTo(selection.onSuccessAppendTo(), className);
        InternalLogger.debug(className.java() + ": transformed to value class (" + out.length + " bytes)");
        return out;
    }

    /**
     * The selection of a loaded class: the effective mode set and the failure
     * settings of the selection source that applied. A class selected by both
     * the annotation and includes is annotation-selected only.
     */
    private record Selection(Set<Mode> effective,
            boolean onFailThrow, String onFailAppendTo, String onSuccessAppendTo) {}

    /** Handles an unexpected failure: rethrows a {@link LinkageError}, otherwise
     *  surfaces a loud failure or leaves the class as identity per the effective
     *  {@code onFail} settings. */
    private byte[] onTransformError(ClassName className, Throwable t,
            boolean onFailThrow, String onFailAppendTo) {
        if (t instanceof LinkageError) {
            // e.g. a superclass was rewritten into a final value class: this
            // class cannot be loaded regardless of on-fail-throw, so surface
            // the (superclass-naming) LinkageError rather than swallowing it.
            throw (LinkageError) t;
        }
        if (onFailThrow) {
            throw new LinkageError("auto-valhalla: failed to transform " + className.java()
                    + " into a value class: " + t, t);
        }
        appendOnFail(className, onFailAppendTo);
        InternalLogger.debug(className.java() + ": transform failed:");
        InternalLogger.error("", t);
        return null;
    }

    /** Handles a selected-but-untransformable class: optionally records the name
     *  and either surfaces a loud failure ({@code onFailThrow}) or leaves the
     *  class as an identity class. {@code onFailThrow} / {@code onFailAppendTo}
     *  come from the class's selection source (annotation vs includes). */
    private byte[] onFail(ClassName className, String reason,
            boolean onFailThrow, String onFailAppendTo) {
        appendOnFail(className, onFailAppendTo);
        if (onFailThrow) {
            InternalLogger.error(className.java() + " " + reason
                    + "; the JVM will reject it rather than silently keep an"
                    + " identity class.");
            // A ClassFileTransformer exception would be swallowed by the JVM, so instead
            // hand back a class file that fails to load, surfacing the failure loudly.
            return brokenClass();
        }
        InternalLogger.debug(className.java() + ": " + reason + ", leaving as identity class");
        return null;
    }

    private void appendOnFail(ClassName className, String onFailAppendTo) {
        appendTo(onFailAppendTo, className);
    }

    /** Appends the Java name of {@code className} to the file at {@code path}
     *  (unless it is already recorded there), deduplicating across runs by reading
     *  the file at start-up. Uses {@link AsyncFileWriter} for non-blocking I/O. */
    private void appendTo(String path, ClassName className) {
        if (path == null || path.isEmpty()) {
            return;
        }
        AsyncFileWriter.forFile(path).record(className.java());
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
    static boolean patternMatches(Set<String> patterns, ClassName className) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        String jvm = className.jvm();
        for (String p : patterns) {
            // patterns may be in dot form (e.g. from tests or config files) or
            // already in slash form (e.g. after AutoValhallaAgent.normalizePattern)
            String norm = p.replace('.', '/');
            if (norm.equals("*")) {
                return true;
            }
            if (norm.endsWith("/")) {
                if (jvm.startsWith(norm)) {
                    return true;
                }
            } else if (norm.contains("/")) {
                if (jvm.equals(norm)) {
                    return true;
                }
            } else if (jvm.equals(norm) || jvm.startsWith(norm + "/")) {
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

    private static boolean isExcluded(ClassName className) {
        String jvm = className.jvm();
        if (jvm.startsWith("java/")
                || jvm.startsWith("javax/")
                || jvm.startsWith("sun/")
                || jvm.startsWith("com/sun/")
                || jvm.startsWith("jdk/")) {
            return true;
        }
        // never transform the agent's own support classes (which include the
        // embedded @AutoValhalla annotation)
        return jvm.startsWith("io/github/thunkware/auto/valhalla/");
    }
}
