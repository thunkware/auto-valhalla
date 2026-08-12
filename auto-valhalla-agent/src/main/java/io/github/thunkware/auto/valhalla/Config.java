package io.github.thunkware.auto.valhalla;

import java.util.EnumSet;
import java.util.Set;

/** Parsed agent configuration: include/exclude patterns, the {@link Mode} sets
 *  applied to annotation-selected vs includes-selected classes, and the
 *  failure-handling flags. */
record Config(Set<String> includes, Set<String> excludes,
        Set<Mode> annotationMode, Set<Mode> includesMode,
        boolean debug, boolean onFailThrow, String onFailAppendTo) {}
