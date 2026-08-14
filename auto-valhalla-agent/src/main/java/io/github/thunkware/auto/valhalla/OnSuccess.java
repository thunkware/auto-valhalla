package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;

/**
 * Controls the log level used when a selected class is successfully converted.
 * Used as the value of {@code annotation.on-success} and {@code includes.on-success}.
 *
 * <ul>
 *   <li>{@code info} — log at INFO (default for both annotation and includes).</li>
 *   <li>{@code debug} — log at DEBUG.</li>
 *   <li>{@code off} — do not log.</li>
 * </ul>
 */
public enum OnSuccess {
    INFO, DEBUG, OFF;

    private static final InternalLogger LOG = InternalLogger.getLogger(OnSuccess.class);

    static OnSuccess parse(String s, OnSuccess defaultValue) {
        if (s == null || s.isBlank()) {
            return defaultValue;
        }
        return switch (s.trim().toLowerCase()) {
            case "info"  -> INFO;
            case "debug" -> DEBUG;
            case "off"   -> OFF;
            default -> {
                LOG.warning("Unknown on-success value '" + s.trim()
                        + "'; using " + defaultValue.name().toLowerCase());
                yield defaultValue;
            }
        };
    }
}
