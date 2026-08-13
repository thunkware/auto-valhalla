package sample;

/**
 * An abstract class used to verify that rewriting an abstract class into an
 * abstract value class lets a later subclass of it (sample.AbstractSub) itself
 * be rewritten, since per JEP 401 a value class may extend an abstract value
 * class.
 */
public abstract class AbstractBase {
    protected final int seed;

    public AbstractBase(int seed) {
        this.seed = seed;
    }
}
