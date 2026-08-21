package fixture;

/**
 * Suitable value-class candidate, but not annotated: the compile-time transform
 * selects {@code @AutoValhalla} classes only, so this is never converted.
 */
public final class Shade {

    public final int r;
    public final int g;
    public final int b;

    public Shade(int r, int g, int b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }
}
