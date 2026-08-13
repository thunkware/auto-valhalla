package demo5.broken;

import demo5.annotation.Point;
import demo5.includes.Square;
import io.github.thunkware.auto.valhalla.AutoValhalla;

/**
 * Deliberately <em>not</em> a JEP 401 value-class candidate even though it is
 * annotated: the non-final field {@code y} is reassigned by {@link #setY(int)}
 * outside the constructor, so it can never be {@code final} (which a value
 * class requires). The agent must therefore reject it and leave it as an
 * identity class rather than emit a value class that would not load.
 *
 * <p>Lives in the {@code demo5.broken} subpackage to keep the deliberately
 * broken fixtures apart from the demo's real value-class candidates
 * ({@link Point}, {@link Square}).
 *
 * <p>Serves as a permanent regression guard for the {@code mark-fields-final}
 * rejection: an annotated mutable class must stay an identity class.
 */
@AutoValhalla
public class MutablePoint {
    private final int x;
    private int y;

    public MutablePoint(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int x() { return x; }
    public int y() { return y; }

    public void setY(int k) {
        y = k;
    }

    @Override
    public String toString() {
        return "MutablePoint(" + x + ", " + y + ")";
    }
}