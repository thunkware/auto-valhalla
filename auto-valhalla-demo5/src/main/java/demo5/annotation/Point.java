package demo5.annotation;

import io.github.thunkware.auto.valhalla.AutoValhalla;

/**
 * Converted to a value class by the {@code @AutoValhalla} annotation.
 * Compiled to genuine Java 5 bytecode to prove the agent
 * handles legacy class files, not just classes built with a modern JDK.
 *
 * <p>Constructors are ordinary (super() first) — the agent reorders them.
 */
@AutoValhalla
public class Point {
    private final int x;
    private final int y;

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int x() { return x; }
    public int y() { return y; }

    @Override
    public String toString() {
        return "Point(" + x + ", " + y + ")";
    }
}
