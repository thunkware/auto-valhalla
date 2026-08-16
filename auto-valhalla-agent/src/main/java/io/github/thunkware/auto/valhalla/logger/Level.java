package io.github.thunkware.auto.valhalla.logger;

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
}
