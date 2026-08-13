package demo16.annotation;

import io.github.thunkware.auto.valhalla.AutoValhalla;

/**
 * Converted to a value class by the {@code @AutoValhalla} annotation. Compiled
 * to Java 16 bytecode to prove the agent handles class files
 * newer than the JDK it was built against.
 */
@AutoValhalla
public final class Money {
    private final long cents;
    private final String currency;

    public Money(long cents, String currency) {
        this.cents = cents;
        this.currency = currency;
    }

    public long cents() { return cents; }
    public String currency() { return currency; }

    @Override
    public String toString() {
        return currency + " " + (cents / 100) + "." + (cents % 100);
    }
}
