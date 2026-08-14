package demo5.broken;

import demo5.annotation.Point;
import demo5.includes.Square;

/**
 * Deliberately <em>not</em> a JEP 401 value-class candidate even though it is
 * immutable: every field is {@code final} and written only in the constructor,
 * but {@link #sum()} and {@link #dot(SyncPoint)} are synchronized instance
 * methods, and a value class cannot declare one (synchronization requires
 * identity). Under modes without {@code remove-synchronized} the agent must
 * therefore reject it rather than emit a value class that would not load.
 *
 * <p>Lives in the {@code demo5.broken} subpackage to keep the deliberately
 * broken fixtures apart from the demo's real value-class candidates
 * ({@link Point}, {@link Square}).
 *
 * <p>Serves as a permanent regression guard for the synchronized-instance-method
 * rejection on genuine Java 5 bytecode (the {@code sample.Sync} fixture covers
 * the same rule with modern bytecode).
 */
public class SyncPoint {
    private final int x;
    private final int y;

    public SyncPoint(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public synchronized int sum() {
        return x + y;
    }

    public synchronized int dot(SyncPoint other) {
        return x * other.x + y * other.y;
    }

    @Override
    public String toString() {
        return "SyncPoint(" + x + ", " + y + ")";
    }
}