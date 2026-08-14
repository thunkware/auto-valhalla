package io.github.thunkware.auto.valhalla;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Modes accepted by the {@code auto-valhalla.annotation-mode} and
 * {@code auto-valhalla.includes-mode} string properties. The
 * {@code @AutoValhalla} annotation and {@code includes} select which classes are
 * candidates; {@code annotation-mode} narrows annotation-selected classes and
 * {@code includes-mode} narrows includes-selected ones. The property may list
 * several comma-separated tokens (case-insensitive; {@code -}, {@code _} and
 * camelCase are all accepted); they are collected into an {@link EnumSet}
 * internally. {@code SYNCHRONIZATION_MONITOR} is exclusive: it cannot be
 * combined with other modes; doing so throws {@link IllegalArgumentException}.
 */
public enum Mode {
    /** Narrow selection to classes that are <em>already final</em> ({@code safe}).
     *  Non-final candidates are not converted; whether that is a silent skip or
     *  a loud failure depends on the configured {@code on-fail-throw} setting. */
    SAFE("safe"),

    /** Shorthand for the default modes
     *  ({@code mark-class-final,ignore-synchronized,mark-fields-final}). Never
     *  appears in a parsed set: {@link #parse(String)} expands it. */
    YOLO("yolo"),

    /** Allow candidates with synchronized instance methods: their
     *  {@code ACC_SYNCHRONIZED} is stripped so they can become value classes. */
    IGNORE_SYNCHRONIZED("ignore-synchronized"),

    /** Allow non-final (non-abstract) candidates to be converted by marking the
     *  class {@code final}, so only opt in when no subclass exists (or you accept
     *  that subclasses will fail to load with an
     *  {@link java.lang.IncompatibleClassChangeError}). Abstract candidates are
     *  never converted (see
     *  {@link ValueClassRewriter#suitabilityProblems(java.lang.classfile.ClassModel, boolean, boolean)}). */
    MARK_CLASS_FINAL("mark-class-final"),

    /** If instance fields are non-{@code final} yet written only once in a
     *  constructor, mark them {@code final}. Candidates where a non-{@code final}
     *  field is written elsewhere (or more than once) are rejected, since a value
     *  class cannot have a mutable field. */
    MARK_FIELDS_FINAL("mark-fields-final"),

    /** Instrument selected classes to monitor synchronization attempts (calls to
     *  {@code monitorenter}). When set and {@code synchronization-monitor.append-to}
     *  is configured, each class with a {@code monitorenter} is instrumented to
     *  record which classes are being synchronized on. <em>Cannot be combined with
     *  other modes</em>; specifying both this and any other mode results in an
     *  {@link IllegalArgumentException}. */
    SYNCHRONIZATION_MONITOR("synchronization-monitor");

    public final String flag;

    Mode(String flag) {
        this.flag = flag;
    }

    /** The default set for {@code annotation-mode} (classes selected by the
     *  {@code @AutoValhalla} annotation): {@code safe} — only classes that are
     *  <em>already final</em> are converted. A selected
     *  non-final class is not converted and is handled by the {@code on-fail-*}
     *  settings (by default a loud failure for annotated classes, so
     *  {@code mark-class-final} must be opted in explicitly to convert it). */
    public static final Set<Mode> ANNOTATION_DEFAULT =
            Collections.unmodifiableSet(EnumSet.of(Mode.SAFE));

    /** The default set for {@code includes-mode} (classes selected by
     *  {@code includes}) — and the {@code yolo} expansion:
     *  {@code mark-class-final,ignore-synchronized,mark-fields-final}. */
    public static final Set<Mode> INCLUDES_DEFAULT =
            Collections.unmodifiableSet(EnumSet.of(Mode.MARK_CLASS_FINAL, Mode.IGNORE_SYNCHRONIZED,
                    Mode.MARK_FIELDS_FINAL));

    /** Parses a mode string into a set of {@link Mode}s using
     *  {@link #INCLUDES_DEFAULT} as the default. */
    public static Set<Mode> parse(String s) {
        return parse(s, INCLUDES_DEFAULT);
    }

    /** Parses a mode string into a set of {@link Mode}s. A {@code null}, blank or
     *  unknown value yields {@code dflt}. {@code yolo} is a shorthand for
     *  {@link #INCLUDES_DEFAULT}. {@code SYNCHRONIZATION_MONITOR} cannot be
     *  combined with other modes; if present and other modes are also present,
     *  an {@link IllegalArgumentException} is thrown. Unknown tokens throw
     *  {@link IllegalArgumentException}. */
    public static Set<Mode> parse(String s, Set<Mode> dflt) {
        EnumSet<Mode> set = EnumSet.noneOf(Mode.class);
        if (s == null || s.isBlank()) {
            return EnumSet.copyOf(dflt);
        }
        Set<String> unknownTokens = new HashSet<>();
        for (String tok : s.split(",")) {
            tok = tok.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (tok.isEmpty()) {
                continue;
            }
            switch (tok) {
                case "safe" -> set.add(Mode.SAFE);
                case "yolo" -> set.add(Mode.YOLO);
                case "markclassfinal" -> set.add(Mode.MARK_CLASS_FINAL);
                case "ignoresynchronized" ->
                        set.add(Mode.IGNORE_SYNCHRONIZED);
                case "markfieldsfinal" -> set.add(Mode.MARK_FIELDS_FINAL);
                case "synchronizationmonitor" -> set.add(Mode.SYNCHRONIZATION_MONITOR);
                default -> unknownTokens.add(tok);
            }
        }
        if (!unknownTokens.isEmpty()) {
            throw new IllegalArgumentException("Unknown mode tokens: " + unknownTokens
                    + "; valid modes are: safe, yolo, mark-class-final, "
                    + "ignore-synchronized, mark-fields-final, synchronization-monitor");
        }
        if (set.contains(Mode.YOLO)) {
            set.remove(Mode.YOLO);
            set.addAll(INCLUDES_DEFAULT);
        }
        if (set.isEmpty()) {
            return EnumSet.copyOf(dflt);
        }
        // SYNCHRONIZATION_MONITOR is exclusive: cannot be combined with other modes
        if (set.contains(Mode.SYNCHRONIZATION_MONITOR)) {
            if (set.size() > 1) {
                throw new IllegalArgumentException(
                        "mode=synchronization-monitor cannot be combined with other modes; "
                        + "got: " + set);
            }
        }
        return set;
    }
}
