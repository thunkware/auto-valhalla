package demo5;

import io.github.thunkware.auto.valhalla.AutoValhalla;

/**
 * Like {@link Square} — a non-final field written exactly once, in the
 * constructor, so it can safely be made {@code final} — but selected by the
 * {@code @AutoValhalla} annotation instead of {@code includes}. Compiled to
 * genuine Java 5 bytecode (major version 49).
 */
@AutoValhalla
public class Circle {
    @SuppressWarnings("FieldMayBeFinal")
    private int radius;

    public Circle(int radius) {
        this.radius = radius;
    }

    public int area() {
        return radius * radius * 3;
    }

    @Override
    public String toString() {
        return "Circle(" + radius + ")";
    }
}