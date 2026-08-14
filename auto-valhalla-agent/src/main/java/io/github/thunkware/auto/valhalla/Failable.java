package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import java.util.concurrent.Callable;

class Failable {

    private static final InternalLogger LOG = InternalLogger.getLogger(Failable.class);
    interface ThrowingRunnable {
        void run() throws Throwable;
    }

    public static void runQuietly(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable e) {
            LOG.debug(e.toString());
        }
    }


    public static <T> T callQuietly(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Throwable e) {
            LOG.debug(e.toString());
            return null;
        }
    }

}
