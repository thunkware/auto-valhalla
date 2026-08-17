package sample;

/**
 * Fixture: a constructor that does work <em>after</em> its last field store.
 * The relocation in {@code ConstructorRewriter} moves the field store ahead of
 * {@code super()}; the trailing {@code bump()} call must stay in the body (and
 * before the return), otherwise the rewritten class verifies and loads but
 * silently loses the side effect.
 */
public final class SideEffect {

    public static int calls;

    private final int x;

    public SideEffect(int x) {
        this.x = x;
        bump();
    }

    public static void bump() {
        calls++;
    }

    public int x() {
        return x;
    }
}
