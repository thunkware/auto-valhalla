package io.github.thunkware.auto.valhalla.util;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import io.github.thunkware.auto.valhalla.logger.InternalLoggerFactory;

import java.util.function.Consumer;

public class Failable {

    private static final InternalLogger LOG = InternalLoggerFactory.getLogger(Failable.class);

    public interface ThrowingRunnable {
        void run() throws Throwable;
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

    public static void runQuietly(ThrowingRunnable runnable) {
        run(runnable, t -> LOG.debug("", t));
    }

    public interface ThrowingCallable<T> {
        T call() throws Throwable;
    }

    public static <T> T callQuietly(ThrowingCallable<T> callable) {
        return callQuietly(callable, null);
    }

    public static <T> T callQuietly(ThrowingCallable<T> callable, T defaultValue) {
        try {
            return callable.call();
        } catch (Throwable t) {
            restoreInterrupt(t);
            LOG.debug("", t);
            return defaultValue;
        }
    }
}
