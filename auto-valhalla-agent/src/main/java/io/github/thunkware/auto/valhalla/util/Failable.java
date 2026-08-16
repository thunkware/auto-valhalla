package io.github.thunkware.auto.valhalla.util;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import io.github.thunkware.auto.valhalla.logger.InternalLoggerFactory;
import java.util.concurrent.Callable;

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

    public static <T> T callQuietly(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Throwable t) {
            LOG.debug("", t);
            return null;
        }
    }

}
