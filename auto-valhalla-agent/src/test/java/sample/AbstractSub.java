package sample;

/**
 * A concrete subclass of {@link AbstractBase}. Because AbstractBase is an
 * identity class (abstract classes are not converted), this subclass is not a
 * value-class candidate either (a value class may extend only java.lang.Object
 * or java.lang.Record).
 */
public class AbstractSub extends AbstractBase {

    public AbstractSub(int seed) {
        super(seed);
    }
}
