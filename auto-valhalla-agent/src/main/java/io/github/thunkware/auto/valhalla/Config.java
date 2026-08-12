package io.github.thunkware.auto.valhalla;

import java.util.Set;

/** Parsed agent configuration: include/exclude patterns, {@link Mode} set and
 *  failure-handling flags. */
record Config(Set<String> includes, Set<String> excludes,
        Set<Mode> mode, boolean debug, boolean onFailThrow, String onFailAppendTo) {}
