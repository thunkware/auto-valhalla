package io.github.thunkware.auto.valhalla;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parsed agent configuration: include/exclude patterns, the {@link Mode} sets
 *  applied to annotation-selected vs includes-selected classes, the append-to
 *  file paths, the synchronization monitor path, and the log level. */
class Config {

    Set<String> includes = new LinkedHashSet<>();
    List<String> includesFiles = new ArrayList<>();
    Set<String> excludes = new LinkedHashSet<>();
    List<String> excludesFiles = new ArrayList<>();
    Set<Mode> annotationMode = EnumSet.copyOf(Mode.ANNOTATION_DEFAULT);
    Set<Mode> includesMode = EnumSet.copyOf(Mode.INCLUDES_DEFAULT);
    String annotationOnFailAppendTo;
    String annotationOnSuccessAppendTo;
    String includesOnFailAppendTo;
    String includesOnSuccessAppendTo;
    String synchronizationMonitorAppendTo = "auto-valhalla.synchronization.txt";
    Map<String, String> loggerLevels = new LinkedHashMap<>();
    String logging;

    /** Canonical option keys (without the {@code auto-valhalla.} prefix), also
     *  used by the {@link AutoValhallaAgent28#parse()} switch. */
    static final String INCLUDES = "includes";
    static final String INCLUDES_FILES = "includes-files";
    static final String EXCLUDES = "excludes";
    static final String EXCLUDES_FILES = "excludes-files";
    static final String ANNOTATION_MODE = "annotation-mode";
    static final String INCLUDES_MODE = "includes-mode";
    /** Prefix for per-logger level overrides: {@code logging.level.<logger-name>=<level>}.
     *  The special name {@code root} sets the global level. */
    static final String LOG_LEVEL_PREFIX = "logging.level.";
    static final String ANNOTATION_ON_FAIL_APPEND_TO = "annotation.on-fail-append-to";
    static final String ANNOTATION_ON_SUCCESS_APPEND_TO = "annotation.on-success-append-to";
    static final String INCLUDES_ON_FAIL_APPEND_TO = "includes.on-fail-append-to";
    static final String INCLUDES_ON_SUCCESS_APPEND_TO = "includes.on-success-append-to";
    static final String SYNCHRONIZATION_MONITOR_APPEND_TO = "synchronization-monitor.append-to";
    static final String LOGGING = "logging";
    static final String CONFIG = "config";

    /** Every canonical option key (without the {@code auto-valhalla.} prefix).
     *  Used to look up system properties / environment variables, to validate
     *  config-file keys, and to warn about unknown {@code auto-valhalla.*}
     *  settings. {@link #CONFIG} is applied before all of them, so an explicit
     *  system property or environment variable overrides the config file. */
    static final List<String> KNOWN = List.of(
            INCLUDES, INCLUDES_FILES, EXCLUDES, EXCLUDES_FILES,
            ANNOTATION_MODE, INCLUDES_MODE,
            LOGGING,
            ANNOTATION_ON_FAIL_APPEND_TO, ANNOTATION_ON_SUCCESS_APPEND_TO,
            INCLUDES_ON_FAIL_APPEND_TO, INCLUDES_ON_SUCCESS_APPEND_TO,
            SYNCHRONIZATION_MONITOR_APPEND_TO,
            CONFIG);
}
