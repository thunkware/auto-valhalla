package demo5;

/**
 * Converted to a value class via {@code includes=demo5} combined with
 * {@code includes-mode=yolo}: the field is non-final yet written exactly once, in
 * the constructor, so it can safely be made {@code final}. Compiled to genuine
 * Java 5 bytecode.
 */
public class Square {
    @SuppressWarnings("FieldMayBeFinal")
    private int side;

    public Square(int side) {
        this.side = side;
    }

    public int area() {
        return side * side;
    }

    @Override
    public String toString() {
        return "Square(" + side + ")";
    }
}
