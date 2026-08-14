package io.github.thunkware.auto.valhalla;

import java.util.concurrent.Callable;

class Failable {
    interface ThrowingRunnable {
        void run() throws Throwable;
    }

    public static void runQuietly(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable e) {
            InternalLogger.debug(e.toString());
        }
    }


    public static <T> T callQuietly(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Throwable e) {
            InternalLogger.debug(e.toString());
            return null;
        }
    }

}
