package io.github.thunkware.auto.valhalla.logger;

enum Level {
    OFF(0),
    FATAL(1),
    ERROR(2),
    WARN(3),
    INFO(4),
    DEBUG(5);

    final int rank;

    Level(int rank) {
        this.rank = rank;
    }
}
