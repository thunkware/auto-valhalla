package fixture;

import io.github.thunkware.auto.valhalla.api.AutoValhalla;

/**
 * Suitable identity class fixture for the compile-time transformation: final
 * class with final instance fields, exactly what {@code safe} mode converts.
 */
@AutoValhalla
public final class Point {

    public final int x;
    public final int y;

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public String toString() {
        return "Point(" + x + ", " + y + ")";
    }
}