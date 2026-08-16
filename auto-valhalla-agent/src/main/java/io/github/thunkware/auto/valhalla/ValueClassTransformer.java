package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.api.AutoValhalla;
import io.github.thunkware.auto.valhalla.logger.InternalLogger;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
 * For the full list of configuration options see {@link AutoValhallaAgent28}.
 *
 * <p>A class selected by both the annotation and {@code includes} is treated as
 * annotation-selected only: its mode set and failure settings come from the
 * annotation (an explicit in-source opt-in is the stronger statement).
 * By default, includes-selected classes that fail verification after rewriting
 * are left untouched, so an unsupported class simply keeps identity semantics
 * instead of failing to load.
 */
public final class ValueClassTransformer implements ClassFileTransformer {

    private final ValueClassTransformerLoggers loggers = new ValueClassTransformerLoggers();
    private final Config config;
    /** Internal names of classes we turned from non-final into final value
     *  classes, so a later subclass load can be reported by superclass name. */
    private final Set<String> transformedToFinal = ConcurrentHashMap.newKeySet();

    ValueClassTransformer(Config cfg) {
        this.config = cfg;
        // Initialize BackgroundFileWriter for each append-to path so files are read
        // at startup (deduplicating against existing names). BackgroundFileWriter is
        // shared per-path, so success and failure appends to the same file
        // deduplicate against each other.
        Stream.of(cfg.annotationOnFailAppendTo, cfg.annotationOnSuccessAppendTo, cfg.includesOnFailAppendTo,
                        cfg.includesOnSuccessAppendTo, cfg.synchronizationMonitorAppendTo)
                .filter(Objects::nonNull)
                .forEach(BackgroundFileWriter::forFile);

        // Activate SynchronizationMonitor when the mode is configured.
        // configure() accepts a null path (log-only, no file), so the condition
        // is on mode usage rather than path presence.
        if (cfg.annotationMode.contains(Mode.SYNCHRONIZATION_MONITOR)
                || cfg.includesMode.contains(Mode.SYNCHRONIZATION_MONITOR)) {
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
        if (!loggers.log().isDebugEnabled()) {
            return doTransform(module, loader, className, classfileBuffer);
        }

        long startTime = System.nanoTime();
        byte[] result = null;
        try {
            result = doTransform(module, loader, className, classfileBuffer);
            return result;
        } finally {
            long durationNano = System.nanoTime() - startTime;
            Stats.onValueClassTransform(durationNano);
            if (result != null) {
                long duration = TimeUnit.NANOSECONDS.toMillis(durationNano);
                loggers.log().debug("Completed transforming " + className.java()
                                            + " in " + duration + "ms (total " + Stats.transformTotalDurationMs() + "ms)");
            }
        }
    }

    private byte[] doTransform(Module module, ClassLoader loader, ClassName className, byte[] classfileBuffer) {
        if (isExcluded(className)) {
            return null;
        }
        // Before a class has been classified as annotation- or includes-selected,
        // failures must not take any loud on-fail setting: a class that is not
        // Pre-selection: all-safe defaults so a parse failure before the class is
        // classified never triggers on-fail-throw or on-fail-warn. Replaced with
        // the real selection once select() returns non-null.
        Selection selection = Selection.empty();
        try {
            ClassModel model = ClassFile.of().parse(classfileBuffer);
            selection = select(className, model);
            if (selection == null) {
                return null;
            }
            // SYNCHRONIZATION_MONITOR is an either-or mode: either rewrite the class
            // to a value class, or instrument monitorenter calls, but not both.
            if (selection.hasMode(Mode.SYNCHRONIZATION_MONITOR)) {
                return monitorSynchronization(model, className, selection, loader);
            }
            return rewrite(className, model, selection, loader);
        } catch (LinkageError e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // Configuration error (e.g. incompatible modes) — always log at warning
            // so it is visible regardless of the class's on-fail setting.
            loggers.log().warning(className.java() + ": configuration error — " + e.getMessage());
            return null;
        } catch (Throwable t) {
            return onTransformError(className, t, selection);
        }
    }

    private byte[] monitorSynchronization(ClassModel model, ClassName className,
            Selection selection, ClassLoader loader) {
        if (!SynchronizationInstrumenter.hasMonitorEnter(model)) {
            return null;
        }
        byte[] monitored = SynchronizationInstrumenter.instrument(model, loader);
        if (monitored != null) {
            loggers.log().debug(className.java() + ": instrumented for synchronization monitoring");
            return monitored;
        }
        return onRejected(className,
                "is selected for synchronization monitoring but could not be"
                        + " instrumented (stack-map verification failed)", selection);
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
        // append-to paths come from the annotation settings.
        String onFailAppendTo;
        String onSuccessAppendTo;
        EnumSet<Mode> effective = EnumSet.noneOf(Mode.class);
        if (annotated) {
            effective.addAll(config.annotationMode);
            onFailAppendTo = config.annotationOnFailAppendTo;
            onSuccessAppendTo = config.annotationOnSuccessAppendTo;
        } else {
            effective.addAll(config.includesMode);
            onFailAppendTo = config.includesOnFailAppendTo;
            onSuccessAppendTo = config.includesOnSuccessAppendTo;
        }
        if (effective.remove(Mode.YOLO)) {
            effective.addAll(Mode.YOLO_DEFAULT);
        }
        // SYNCHRONIZATION_MONITOR cannot be used in combination with other modes
        if (effective.contains(Mode.SYNCHRONIZATION_MONITOR) && effective.size() > 1) {
            throw new IllegalArgumentException(
                    "mode=synchronization-monitor cannot be combined with other modes; "
                    + "got: " + effective);
        }
        return new Selection(effective, onFailAppendTo, onSuccessAppendTo, annotated);
    }

    /**
     * Rewrites a selected class: runs the suitability checks, applies the
     * configured modes, and records classes we turned into final value classes so
     * later subclass loads can be reported by superclass name. Failure handling
     * follows the selection's settings.
     */
    private byte[] rewrite(ClassName className, ClassModel model, Selection selection,
            ClassLoader loader) {
        boolean ignoreSync = selection.hasMode(Mode.REMOVE_SYNCHRONIZED);
        boolean markClassFinal = selection.hasMode(Mode.MARK_CLASS_FINAL);
        List<String> problems = ValueClassRewriter.suitabilityProblems(
                model, ignoreSync, markClassFinal);
        if (!problems.isEmpty()) {
            return onRejected(className, "is selected for value-class transformation but is not"
                    + " suitable: " + String.join("; ", problems), selection);
        }
        if (selection.hasMode(Mode.MARK_FIELDS_FINAL)
                && !ValueClassRewriter.fieldsSafeToMarkFinal(model)) {
            return onRejected(className,
                    "is selected for value-class transformation but has a non-final field"
                    + " not written in every constructor (mode=mark-fields-final)", selection);
        }
        InternalLogger rejectedLog = loggers.rejected(selection);
        byte[] out = ValueClassRewriter.transform(model, rejectedLog.isFatal(),
                ignoreSync, markClassFinal, loader);
        if (out == null) {
            return onRejected(className,
                    "is selected for value-class transformation but could not be safely"
                    + " transformed", selection);
        }
        // Record what we turned classes into so later loads can reason about
        // them: a non-final class becomes a final value class (its subclasses
        // stop loading). Abstract classes are never converted.
        if (!model.flags().has(AccessFlag.FINAL)) {
            transformedToFinal.add(className.jvm());
        }
        appendTo(selection.onSuccessAppendTo(), className);
        String successMsg = "Transformed to value class: " + className.java();
        loggers.success(selection).info(successMsg);
        return out;
    }

    /**
     * The selection of a loaded class: the effective mode set, the append-to file
     * paths, and the selection source. A class selected by both the annotation and
     * includes is annotation-selected only.
     */
    record Selection(
            Set<Mode> effective,
            String onFailAppendTo,
            String onSuccessAppendTo,
            boolean annotated) {
        static Selection empty() {
            return new Selection(Collections.emptySet(), null, null, false);
        }

        boolean hasMode(Mode mode) {
            return effective.contains(mode);
        }
    }

    /** Handles an unexpected failure: rethrows a {@link LinkageError}, otherwise
     *  reports and leaves the class as identity (or rejects it when the fail logger
     *  is set to {@code fatal}). */
    private byte[] onTransformError(ClassName className, Throwable t, Selection selection) {
        if (t instanceof LinkageError le) {
            // e.g. a superclass was rewritten into a final value class: this
            // class cannot be loaded regardless of level, so surface the
            // (superclass-naming) LinkageError rather than swallowing it.
            throw le;
        }
        InternalLogger failLog = loggers.fail(selection);
        if (failLog.isFatal()) {
            throw new LinkageError("auto-valhalla: failed to transform " + className.java()
                    + " into a value class: " + t, t);
        }
        appendOnFail(className, selection.onFailAppendTo());
        failLog.logAtEffectiveLevel("Transform failed: " + className.java(), t);
        return null;
    }

    /** Handles a selected-but-untransformable class: records the name if configured,
     *  then either causes a load failure (when the rejected logger is at {@code fatal})
     *  or logs at the configured level and leaves the class as an identity class. */
    private byte[] onRejected(ClassName className, String reason, Selection selection) {
        appendOnFail(className, selection.onFailAppendTo());
        String base = className.java() + ": " + reason;
        InternalLogger rejectedLog =
                loggers.rejected(selection);
        if (rejectedLog.isFatal()) {
            loggers.log().error(base + "; the JVM will reject it rather than silently keep an identity class.");
            // A ClassFileTransformer exception would be swallowed by the JVM, so
            // hand back a class file that fails to load, surfacing the failure loudly.
            rejectedLog.warning(base + "; the JVM will reject it");
            return brokenClass();
        }
        rejectedLog.logAtEffectiveLevel(base + ", leaving as identity class");
        return null;
    }

    private void appendOnFail(ClassName className, String onFailAppendTo) {
        appendTo(onFailAppendTo, className);
    }

    /** Appends the Java name of {@code className} to the file at {@code path}
     *  (unless it is already recorded there), deduplicating across runs by reading
     *  the file at start-up. Uses {@link BackgroundFileWriter} for non-blocking I/O. */
    private void appendTo(String path, ClassName className) {
        if (path == null || path.isEmpty()) {
            return;
        }
        BackgroundFileWriter.forFile(path).record(className.java());
    }

    /**
     * Returns true if {@code className} matches any pattern in {@code patterns}.
     * Patterns may use either dots ({@code com.example.Foo}) or slashes
     * ({@code com/example/Foo}); both are normalized to internal-name form.
     * <ul>
     *   <li>{@code *} matches everything;</li>
     *   <li>a pattern ending in {@code .} or {@code /} is a package-prefix match
     *       (includes sub-packages);</li>
     *   <li>otherwise the pattern matches if it equals the class's JVM name
     *       (exact class), or if the class's JVM package name equals the pattern
     *       or starts with the pattern followed by {@code /} (recursive package
     *       match).</li>
     * </ul>
     */
    static boolean patternMatches(Set<String> patterns, ClassName className) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        String jvm = className.jvm();
        String pkg = className.packageName();
        for (String p : patterns) {
            // patterns may be in dot form (e.g. from tests or config files) or
            // already in slash form (e.g. after AutoValhallaAgent28.normalizePattern)
            String norm = p.replace('.', '/');
            if (norm.equals("*")) {
                return true;
            }
            if (norm.endsWith("/")) {
                if (jvm.startsWith(norm)) {
                    return true;
                }
            } else if (jvm.equals(norm) || pkg.equals(norm) || pkg.startsWith(norm + "/")) {
                return true;
            }
        }
        return false;
    }

    /** An unloadable class file, used to force a {@link java.lang.ClassFormatError} when
     *  the rejected logger is at {@code fatal} and a selected class cannot be transformed. */
    private static byte[] brokenClass() {
        return new byte[] { 0, 0, 0, 0 };
    }

    private static boolean isExcluded(ClassName className) {
        String jvm = className.jvm();
        if (jvm.startsWith("java/")
                || jvm.startsWith("javax/")
                || jvm.startsWith("sun/")
                || jvm.startsWith("com/sun/")
                || jvm.startsWith("org/springframework/boot/loader")
                || jvm.startsWith("jdk/")) {
            return true;
        }
        // never transform the agent's own support classes (which include the
        // embedded @AutoValhalla annotation)
        return jvm.startsWith("io/github/thunkware/auto/valhalla/");
    }
}
