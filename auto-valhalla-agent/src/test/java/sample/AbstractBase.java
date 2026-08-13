package sample;

/**
 * An abstract class used to verify that abstract classes are <em>not</em>
 * converted into value classes (an agent-converted abstract value class whose
 * identity subclass loads later triggers a duplicate class definition in the
 * JVM), so they stay identity classes.
 */
public abstract class AbstractBase {
    protected final int seed;

    public AbstractBase(int seed) {
        this.seed = seed;
    }
}
