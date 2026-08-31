package io.github.thunkware.auto.valhalla.maven.support;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.maven.plugin.logging.Log;

public final class LogTool {

    private LogTool() {
        throw new AssertionError();
    }

    public static void info(Log log, String format, Object... arguments) {
        log(log::isInfoEnabled, log::info, log::info, format, arguments);
    }

    public static void debug(Log log, String format, Object... arguments) {
        log(log::isDebugEnabled, log::debug, log::debug, format, arguments);
    }

    public static void debug(Log log, Throwable throwable) {
        if (log.isDebugEnabled()) {
            log.debug(throwable);
        }
    }

    public static void warn(Log log, String format, Object... arguments) {
        log(log::isWarnEnabled, log::warn, log::warn, format, arguments);
    }

    private static void log(BooleanSupplier isEnabled,
                            Consumer<String> logString,
                            BiConsumer<String, Throwable> logStringThrowable,
                            String format, Object... arguments) {
        if (isEnabled.getAsBoolean()) {
            String message = getMessage(format, arguments);
            Throwable throwable = getThrowable(arguments);
            if (throwable != null) {
                logStringThrowable.accept(message, throwable);
            } else {
                logString.accept(message);
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
