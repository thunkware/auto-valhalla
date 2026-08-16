package io.github.thunkware.auto.valhalla.util;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import io.github.thunkware.auto.valhalla.logger.InternalLoggerFactory;

public class Failable {

    private static final InternalLogger LOG = InternalLoggerFactory.getLogger(Failable.class);

    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    public static void runQuietly(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            LOG.debug("", t);
        }
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
            LOG.debug("", t);
            return defaultValue;
        }
    }
}
