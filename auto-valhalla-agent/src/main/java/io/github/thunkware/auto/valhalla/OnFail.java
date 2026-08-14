package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;

/**
 * Controls how a failure to transform a selected class is reported.
 * Used as the value of {@code annotation.on-fail} and {@code includes.on-fail}.
 *
 * <ul>
 *   <li>{@code throw} — cause a {@link java.lang.LinkageError}, preventing the class
 *       from loading (default for annotation-selected classes).</li>
 *   <li>{@code error} — log at ERROR and leave the class as an identity class.</li>
 *   <li>{@code warning} — log at WARNING and leave the class as an identity class.</li>
 *   <li>{@code info} — log at INFO and leave the class as an identity class.</li>
 *   <li>{@code debug} — log at DEBUG and leave the class as an identity class
 *       (default for includes-selected classes).</li>
 *   <li>{@code off} — do nothing; the class is silently left as an identity class.</li>
 * </ul>
 */
enum OnFail {
    THROW, ERROR, WARNING, INFO, DEBUG, OFF;

    static OnFail parse(String s, OnFail defaultValue) {
        if (s == null || s.isBlank()) {
            return defaultValue;
        }
        return switch (s.trim().toLowerCase()) {
            case "throw"           -> THROW;
            case "error"           -> ERROR;
            case "warning", "warn" -> WARNING;
            case "info"            -> INFO;
            case "debug"           -> DEBUG;
            case "off"             -> OFF;
            default -> {
                InternalLogger.warning("Unknown on-fail value '" + s.trim()
                        + "'; using " + defaultValue.name().toLowerCase());
                yield defaultValue;
            }
        };
    }
}
