package sample;

/**
 * Fixture: a {@code final} class with a public (non-private) mutable field. The
 * rewrite marks every non-static field final and strict, which would make a
 * sibling class writing the field fail with {@code IllegalAccessError}, so the
 * agent must reject such a class even though it is already final.
 */
public final class PublicField {
    public int value;

    public PublicField(int value) {
        this.value = value;
    }
}
