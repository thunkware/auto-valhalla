package demo5.includes;

/**
 * Like {@link Square}: a non-final field written exactly once, in the
 * constructor, so it can safely be made {@code final} when `mode=mark-class-final`.
 * Compiled to Java 5 bytecode.
 */
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