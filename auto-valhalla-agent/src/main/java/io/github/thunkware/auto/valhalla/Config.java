package io.github.thunkware.auto.valhalla;

import java.util.List;
import java.util.Set;

/** Parsed agent configuration: include/exclude patterns, the {@link Mode} sets
 *  applied to annotation-selected vs includes-selected classes, the per-source
 *  failure-handling flags, the synchronization monitor path, and the log level. */
class Config {

    Set<String> includes;
    Set<String> excludes;
    Set<Mode> annotationMode;
    Set<Mode> includesMode;
    String logLevel;
    boolean annotationOnFailThrow;
    String annotationOnFailAppendTo;
    String annotationOnSuccessAppendTo;
    boolean includesOnFailThrow;
    String includesOnFailAppendTo;
    String includesOnSuccessAppendTo;
    String synchronizationMonitorAppendTo;

    /** Canonical option keys (without the {@code auto-valhalla.} prefix), also
     *  used by {@link AutoValhallaAgent#parse(String)} switch. */
    static final String INCLUDES = "includes";
    static final String INCLUDES_FILES = "includes-files";
    static final String EXCLUDES = "excludes";
    static final String EXCLUDES_FILES = "excludes-files";
    static final String ANNOTATION_MODE = "annotation-mode";
    static final String INCLUDES_MODE = "includes-mode";
    static final String LOG_LEVEL = "log-level";
    static final String ANNOTATION_ON_FAIL_THROW = "annotation.on-fail-throw";
    static final String ANNOTATION_ON_FAIL_APPEND_TO = "annotation.on-fail-append-to";
    static final String ANNOTATION_ON_SUCCESS_APPEND_TO = "annotation.on-success-append-to";
    static final String INCLUDES_ON_FAIL_THROW = "includes.on-fail-throw";
    static final String INCLUDES_ON_FAIL_APPEND_TO = "includes.on-fail-append-to";
    static final String INCLUDES_ON_SUCCESS_APPEND_TO = "includes.on-success-append-to";
    static final String SYNCHRONIZATION_MONITOR_APPEND_TO = "synchronization-monitor.append-to";
    static final String CONFIG = "config";

    /** Canonical option keys (without the {@code auto-valhalla.} prefix), in
     *  precedence order: system properties / environment variables are looked up
     *  in this order. */
    static final List<String> KNOWN = List.of(
            INCLUDES, INCLUDES_FILES, EXCLUDES, EXCLUDES_FILES,
            ANNOTATION_MODE, INCLUDES_MODE,
            LOG_LEVEL,
            ANNOTATION_ON_FAIL_THROW, ANNOTATION_ON_FAIL_APPEND_TO,
            ANNOTATION_ON_SUCCESS_APPEND_TO,
            INCLUDES_ON_FAIL_THROW, INCLUDES_ON_FAIL_APPEND_TO,
            INCLUDES_ON_SUCCESS_APPEND_TO,
            SYNCHRONIZATION_MONITOR_APPEND_TO,
            CONFIG);
}
