package sample;

/**
 * Fixture: a plain identity class that is otherwise value-class compatible (final,
 * field initialized in the constructor) but uses a {@code synchronized} block
 * (bytecode {@code monitorenter}). A value object has no identity, so the JVM
 * throws {@code java.lang.IdentityException} when synchronizing on one; the agent
 * must reject such a class even under {@code mode=remove-synchronized} (which
 * only strips {@code ACC_SYNCHRONIZED}, not {@code monitorenter}).
 */
public final class SyncBlock {
    private final Object value;

    public SyncBlock() {
        value = new Object();
    }

    public void block() {
        synchronized (this) {
            value.toString();
        }
    }
}
