package fixture;

import io.github.thunkware.auto.valhalla.api.AutoValhalla;

/**
 * Annotation-selected but rejected by the JDK 28 compiler: a value class cannot
 * have a synchronized instance method. An annotation-selected class like this
 * must fail the build by default (failOnAnnotationFailure=true).
 */
@AutoValhalla
public final class SyncPoint {

    public final int x;

    public SyncPoint(int x) {
        this.x = x;
    }

    public synchronized int doubled() {
        return x * 2;
    }
}