package io.github.thunkware.auto.valhalla.logger;

import java.util.Locale;

public enum Level {
    OFF(0),
    FATAL(1),
    ERROR(2),
    WARN(3),
    INFO(4),
    DEBUG(5),
    TRACE(6);

    final int rank;

    Level(int rank) {
        this.rank = rank;
    }

    public static Level find(String name) {
        name = name.trim().toUpperCase(Locale.ROOT);
        if (name.equals("WARNING")) {
            return WARN;
        }
        return Level.valueOf(name);
    }
}
