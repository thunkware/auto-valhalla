package io.github.thunkware.auto.valhalla;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Modes accepted by the {@code auto-valhalla.mode} string property. The
 * {@code @AutoValhalla} annotation and {@code includes} select which classes are
 * candidates; {@code mode} further narrows which of those candidates are
 * actually converted. The property may list several comma-separated tokens
 * (case-insensitive; {@code -}, {@code _} and camelCase are all accepted); they
 * are collected into an {@link EnumSet} internally.
 */
public enum Mode {
    /** Narrow selection to classes that are <em>already final</em> ({@code safe}).
     *  Non-final candidates are skipped, because making a non-final class final
     *  would break its subclasses. */
    SAFE("safe"),

    /** Shorthand for the default modes
     *  ({@code ignore-non-final,ignore-synchronized,mark-fields-final}). Never
     *  appears in a parsed set: {@link #parse(String)} expands it. */
    YOLO("yolo"),

    /** Allow non-final candidates to be converted. They are made final, so only
     *  opt in when no subclass exists (or you accept that subclasses will fail to
     *  load with an {@link java.lang.IncompatibleClassChangeError}). */
    IGNORE_NON_FINAL("ignore-non-final"),

    /** Allow candidates with synchronized instance methods: their
     *  {@code ACC_SYNCHRONIZED} is stripped so they can become value classes. */
    IGNORE_SYNCHRONIZED("ignore-synchronized"),

    /** If instance fields are non-{@code final} yet written only once in a
     *  constructor, mark them {@code final}. Candidates where a non-{@code final}
     *  field is written elsewhere (or more than once) are rejected, since a value
     *  class cannot have a mutable field. */
    MARK_FIELDS_FINAL("mark-fields-final");

    public final String flag;

    Mode(String flag) {
        this.flag = flag;
    }

    /** The default {@code mode} set when the option is not specified — and the
     *  {@code yolo} expansion:
     *  {@code ignore-non-final,ignore-synchronized,mark-fields-final}. */
    static Set<Mode> getDefaultModes() {
        return EnumSet.of(Mode.IGNORE_NON_FINAL, Mode.IGNORE_SYNCHRONIZED,
                Mode.MARK_FIELDS_FINAL);
    }

    /** Parses the {@code mode} string property into a set of {@link Mode}s. A
     *  {@code null}, blank or unknown value yields {@link #getDefaultModes()}.
     *  {@code yolo} is a shorthand for the default {@code ignore-*} modes. */
    public static Set<Mode> parse(String s) {
        EnumSet<Mode> set = EnumSet.noneOf(Mode.class);
        if (s == null || s.isBlank()) {
            return getDefaultModes();
        }
        for (String tok : s.split(",")) {
            tok = tok.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (tok.isEmpty()) {
                continue;
            }
            switch (tok) {
                case "safe" -> set.add(Mode.SAFE);
                case "yolo" -> set.add(Mode.YOLO);
                case "ignorenonfinal" -> set.add(Mode.IGNORE_NON_FINAL);
                case "ignoresynchronized" ->
                        set.add(Mode.IGNORE_SYNCHRONIZED);
                case "markfieldsfinal" -> set.add(Mode.MARK_FIELDS_FINAL);
                default -> { /* unknown token ignored */ }
            }
        }
        if (set.contains(Mode.YOLO)) {
            set.remove(Mode.YOLO);
            set.addAll(getDefaultModes());
        }
        return set.isEmpty() ? getDefaultModes() : set;
    }
}
