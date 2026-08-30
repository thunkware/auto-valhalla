package io.github.thunkware.auto.valhalla.maven.support;

import java.util.function.Consumer;

public final class Failable {

    private Failable() {
        throw new AssertionError();
    }

    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    public static void run(ThrowingRunnable runnable) {
        run(runnable, e -> {
        });
    }

    public static void run(ThrowingRunnable runnable, Consumer<Throwable> onFail) {
        try {
            runnable.run();
        } catch (Throwable t) {
            restoreInterrupt(t);
            onFail.accept(t);
        }
    }

    /**
     * Re-asserts the interrupt flag, which throwing an {@link InterruptedException}
     * clears. Without this, swallowing the exception leaves the agent's background
     * loops — which poll {@link Thread#isInterrupted()} — running forever after
     * they have been asked to stop.
     */
    private static void restoreInterrupt(Throwable t) {
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

}
