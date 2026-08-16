package io.github.thunkware.auto.valhalla.logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.time.ZonedDateTime;

/**
 * An {@link InternalLogger} that delegates to SLF4J, discovered reflectively from
 * the thread's context class loader so the agent never links against SLF4J
 * directly. Every logging method — including {@link #isDebugEnabled()} — is
 * delegated to the underlying SLF4J logger. When SLF4J is not available the
 * message falls back to stderr.
 *
 * <p>The reflective method handles are cached once (see {@link #init()}); a
 * {@link #reinstall()} call resets them and is invoked by
 * {@link InternalLoggerFactory#reinstall()} once the application's logging
 * framework is confirmed ready, so early startup messages are not permanently
 * bound to a substitute or NOP logger.
 */
final class Slf4jInternalLogger extends AbstractInternalLogger {

    private static volatile int version = 0;
    private static volatile boolean attempted;
    private static volatile Method getLoggerMethod;
    private static volatile MethodHandle warnHandle;
    private static volatile MethodHandle warnWithCauseHandle;
    private static volatile MethodHandle errorHandle;
    private static volatile MethodHandle errorWithCauseHandle;
    private static volatile MethodHandle infoHandle;
    private static volatile MethodHandle debugHandle;
    private static volatile MethodHandle debugWithCauseHandle;
    private static volatile MethodHandle traceHandle;
    private static volatile MethodHandle traceWithCauseHandle;
    private static volatile MethodHandle isTraceEnabledHandle;
    private static volatile MethodHandle isDebugEnabledHandle;
    private static volatile MethodHandle isInfoEnabledHandle;
    private static volatile MethodHandle isWarnEnabledHandle;
    private static volatile MethodHandle isErrorEnabledHandle;

    private volatile Object slf4jLogger;
    private volatile int slf4jVersion = -1;

    Slf4jInternalLogger(String name) {
        super(name);
    }

    @Override
    public boolean isDebugEnabled() {
        return isEnabled(Level.DEBUG, isDebugEnabledHandle);
    }

    private boolean isEnabledForLevel(Level level) {
        return switch (level) {
            case TRACE -> isTraceEnabled();
            case DEBUG -> isDebugEnabled();
            case INFO -> isInfoEnabled();
            case WARN -> isWarnEnabled();
            case ERROR -> isErrorEnabled();
            case FATAL -> InternalLoggerFactory.effectiveLevel(name) == Level.FATAL;
            case OFF -> !isErrorEnabled() && !isWarnEnabled() && !isInfoEnabled()
                    && !isDebugEnabled() && !isTraceEnabled();
        };
    }

    private boolean isTraceEnabled() {
        return isEnabled(Level.TRACE, isTraceEnabledHandle);
    }

    private boolean isInfoEnabled() {
        return isEnabled(Level.INFO, isInfoEnabledHandle);
    }

    private boolean isWarnEnabled() {
        return isEnabled(Level.WARN, isWarnEnabledHandle);
    }

    private boolean isErrorEnabled() {
        return isEnabled(Level.ERROR, isErrorEnabledHandle);
    }

    private boolean isEnabled(Level lv, MethodHandle handle) {
        Object logger = slf4jLogger();
        if (logger == null || handle == null) {
            return InternalLoggerFactory.effectiveLevel(name).rank >= lv.rank;
        }
        try {
            return (boolean) handle.invokeWithArguments(logger);
        } catch (Throwable e) {
            return InternalLoggerFactory.effectiveLevel(name).rank >= lv.rank;
        }
    }

    @Override
    void logDirect(Level lv, String msg, Throwable t, ZonedDateTime timestamp) {
        if (invoke(lv, msg, t)) {
            return;
        }
        logToStderr(name, lv, msg, t, timestamp);
    }

    private Object slf4jLogger() {
        int v = version;
        if (slf4jVersion != v) {
            slf4jLogger = getLoggerInstance(name);
            slf4jVersion = v;
        }
        return slf4jLogger;
    }

    private boolean invoke(Level level, String msg, Throwable t) {
        Object logger = slf4jLogger();
        if (logger == null) {
            return false;
        }
        try {
            MethodHandle handle;
            if (t != null) {
                handle = switch (level) {
                    case FATAL, ERROR -> errorWithCauseHandle;
                    case WARN -> warnWithCauseHandle;
                    case DEBUG -> debugWithCauseHandle;
                    case TRACE -> traceWithCauseHandle;
                    default -> null;
                };
                if (handle == null) {
                    return false;
                }
                handle.invokeWithArguments(logger, msg, t);
            } else {
                handle = switch (level) {
                    case FATAL, ERROR -> errorHandle;
                    case WARN -> warnHandle;
                    case INFO -> infoHandle;
                    case DEBUG -> debugHandle;
                    case TRACE -> traceHandle;
                    default -> null;
                };
                if (handle == null) {
                    return false;
                }
                handle.invokeWithArguments(logger, msg);
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static Object getLoggerInstance(String name) {
        if (!attempted) {
            init();
        }
        if (getLoggerMethod == null) {
            return null;
        }
        try {
            return getLoggerMethod.invoke(null, name);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Resets all cached SLF4J state and immediately re-initializes from the
     * current thread context classloader. Called by
     * {@link InternalLoggerFactory#reinstall()} when the application's logging
     * framework is ready.
     */
    static synchronized void reinstall() {
        getLoggerMethod = null;
        warnHandle = null;
        warnWithCauseHandle = null;
        errorHandle = null;
        errorWithCauseHandle = null;
        infoHandle = null;
        debugHandle = null;
        debugWithCauseHandle = null;
        traceHandle = null;
        traceWithCauseHandle = null;
        isTraceEnabledHandle = null;
        isDebugEnabledHandle = null;
        isInfoEnabledHandle = null;
        isWarnEnabledHandle = null;
        isErrorEnabledHandle = null;
        attempted = false;
        version++; // triggers lazy re-acquire in all Slf4jInternalLogger instances
        init();    // reentrant: same thread holds the lock
    }

    private static synchronized void init() {
        if (attempted) {
            return;
        }
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = ClassLoader.getSystemClassLoader();
            }
            Class<?> factory = Class.forName("org.slf4j.LoggerFactory", false, cl);
            getLoggerMethod = factory.getMethod("getLogger", String.class);
            // Obtain a probe logger and discover methods via MethodHandles.findVirtual().
            // findVirtual() resolves by name+descriptor without calling getDeclaredMethods0(),
            // so org.slf4j.event.Level is never loaded through LaunchedClassLoader's
            // JarUrlClassLoader — which would throw a duplicate class definition LinkageError
            // in Spring Boot.
            Object probe = getLoggerMethod.invoke(null, "io.github.thunkware.auto.valhalla");
            Class<?> cls = probe.getClass();
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodType str = MethodType.methodType(void.class, String.class);
            MethodType strCause = MethodType.methodType(void.class, String.class, Throwable.class);
            MethodType bool = MethodType.methodType(boolean.class);
            warnHandle = lookup.findVirtual(cls, "warn", str);
            warnWithCauseHandle = lookup.findVirtual(cls, "warn", strCause);
            errorHandle = lookup.findVirtual(cls, "error", str);
            errorWithCauseHandle = lookup.findVirtual(cls, "error", strCause);
            infoHandle = lookup.findVirtual(cls, "info", str);
            debugHandle = lookup.findVirtual(cls, "debug", str);
            debugWithCauseHandle = lookup.findVirtual(cls, "debug", strCause);
            traceHandle = lookup.findVirtual(cls, "trace", str);
            traceWithCauseHandle = lookup.findVirtual(cls, "trace", strCause);
            isTraceEnabledHandle = lookup.findVirtual(cls, "isTraceEnabled", bool);
            isDebugEnabledHandle = lookup.findVirtual(cls, "isDebugEnabled", bool);
            isInfoEnabledHandle = lookup.findVirtual(cls, "isInfoEnabled", bool);
            isWarnEnabledHandle = lookup.findVirtual(cls, "isWarnEnabled", bool);
            isErrorEnabledHandle = lookup.findVirtual(cls, "isErrorEnabled", bool);
        } catch (Throwable e) {
            // SLF4J not available or classloader conflict; caller falls back to stderr
        } finally {
            attempted = true;
        }
    }
}
