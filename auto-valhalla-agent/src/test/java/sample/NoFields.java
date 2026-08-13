package sample;

/**
 * Fixture: a final class with no instance fields (only a static member). A value
 * class must have instance state to flatten, and a field-less class is almost
 * always a static utility, so the agent must reject it.
 */
public final class NoFields {
    public static int id() {
        return 1;
    }
}
