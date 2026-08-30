package io.github.thunkware.auto.valhalla.maven.support;

import org.apache.maven.plugin.logging.Log;

public final class LogTool {

    private LogTool() {
        throw new AssertionError();
    }

    public static void info(Log log, String format, Object... arguments) {
        if (log.isInfoEnabled()) {
            String message = getMessage(format, arguments);
            Throwable throwable = getThrowable(arguments);
            if (throwable != null) {
                log.info(message, throwable);
            } else {
                log.info(message);
            }
        }
    }

    public static void debug(Log log, String format, Object... arguments) {
        if (log.isDebugEnabled()) {
            String message = getMessage(format, arguments);
            Throwable throwable = getThrowable(arguments);
            if (throwable != null) {
                log.debug(message, throwable);
            } else {
                log.debug(message);
            }
        }
    }

    public static void debug(Log log, Throwable throwable) {
        if (log.isDebugEnabled()) {
            log.debug(throwable);
        }
    }

    public static void warn(Log log, String format, Object... arguments) {
        if (log.isWarnEnabled()) {
            String message = getMessage(format, arguments);
            Throwable throwable = getThrowable(arguments);
            if (throwable != null) {
                log.warn(message, throwable);
            } else {
                log.warn(message);
            }
        }
    }

    private static Throwable getThrowable(Object[] arguments) {
        Object lastArgument = arguments.length == 0 ? null : arguments[arguments.length - 1];
        if (lastArgument instanceof Throwable) {
            return (Throwable) lastArgument;
        }
        return null;
    }

    private static String getMessage(String format, Object[] arguments) {
        String newFormat = format.replace("{}", "%s");
        return "auto-valhalla: " + String.format(newFormat, arguments);
    }
}
