package sample;

/**
 * A concrete subclass of {@link AbstractBase}. After the agent rewrites
 * AbstractBase into an abstract value class, this subclass remains a legal
 * value-class candidate (a value class may extend an abstract value class).
 */
public class AbstractSub extends AbstractBase {

    public AbstractSub(int seed) {
        super(seed);
    }
}
