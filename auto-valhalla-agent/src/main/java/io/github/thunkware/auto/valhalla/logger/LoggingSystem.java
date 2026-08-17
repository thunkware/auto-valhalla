package io.github.thunkware.auto.valhalla.logger;

public enum LoggingSystem {
    SIMPLE,
    NONE,
    APPLICATION;

    public static LoggingSystem findOrNull(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase()) {
            case "simple" -> SIMPLE;
            case "none" -> NONE;
            case "application" -> APPLICATION;
            default -> null;
        };
    }
}
