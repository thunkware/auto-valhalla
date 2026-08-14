package sample;

/**
 * Fixture: a plain identity class whose instance methods are synchronized. Such a
 * class cannot become a value class (JEP 401 forbids synchronized instance
 * methods), so the agent must reject it and leave it as a valid identity class
 * rather than producing an unloadable value-class file.
 *
 * <p>With {@code mode=remove-synchronized} the synchronized modifier is stripped
 * from the non-static methods and the class is eligible (its field is initialized
 * in the constructor), so it can be rewritten into a value class. The static
 * synchronized method is left untouched.
 */
public class Sync {
    private final int value;

    public Sync() {
        value = 0;
    }

    public synchronized int get() {
        return value;
    }

    public synchronized void instance() {
    }

    public static synchronized void staticMethod() {
    }
}
