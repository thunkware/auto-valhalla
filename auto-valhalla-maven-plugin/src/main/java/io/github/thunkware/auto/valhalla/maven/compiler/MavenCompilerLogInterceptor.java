package io.github.thunkware.auto.valhalla.maven.compiler;

import static io.github.thunkware.auto.valhalla.maven.support.LogTool.debug;
import static io.github.thunkware.auto.valhalla.maven.support.Undocumented.undocumented;

import io.github.thunkware.auto.valhalla.maven.support.Failable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.logging.Log;

final class MavenCompilerLogInterceptor {

    public static final String COMPILER_MOJO = "org.apache.maven.plugin.compiler.CompilerMojo";
    public static final String TEST_COMPILER_MOJO = "org.apache.maven.plugin.compiler.TestCompilerMojo";

    private static final Object SHARED_INSTALL_LOCK = new Object();
    private static int sharedInstallCount = 0;
    private static Field sharedField;
    private static BuildPluginManager sharedBuildPluginManager;
    private static MavenPluginManager sharedMavenPluginManager;
    private static final ThreadLocal<MavenCompilerLogInterceptor> THIS_INTERCEPTOR = new ThreadLocal<>();

    private final Log interceptorLog;
    private Mojo mojo;
    private Log oldMojoLog;
    private boolean installed;

    MavenCompilerLogInterceptor(Log log) {
        this.interceptorLog = log;
    }

    void installLogInterceptor(BuildPluginManager buildPluginManager) {
        if (undocumented("disableMavenCompilerLogInterceptor")) {
            return;
        }

        synchronized (SHARED_INSTALL_LOCK) {
            if (installed) {
                return;
            }
            boolean isFirstInstall = sharedInstallCount == 0;
            if (isFirstInstall) {
                try {
                    sharedBuildPluginManager = buildPluginManager;
                    sharedField = buildPluginManager.getClass().getDeclaredField("mavenPluginManager");
                    ensureAccessible(sharedField);
                    sharedMavenPluginManager = (MavenPluginManager) sharedField.get(buildPluginManager);

                    InvocationHandler handler = getSharedInvocationHandler();
                    Class<?>[] ifaces = {MavenPluginManager.class};
                    MavenPluginManager newMavenPluginManager = (MavenPluginManager) Proxy.newProxyInstance(MavenPluginManager.class.getClassLoader(), ifaces, handler);
                    set(sharedField, buildPluginManager, newMavenPluginManager);
                } catch (Exception e) {
                    debug(interceptorLog, e);
                    if (sharedField != null && sharedMavenPluginManager != null) {
                        Failable.run(() -> set(sharedField, sharedBuildPluginManager, sharedMavenPluginManager));
                    }
                    sharedField = null;
                    sharedMavenPluginManager = null;
                    sharedBuildPluginManager = null;
                    return; // early return
                }
            }

            // Only count once the reflection and proxy setup
            // succeeded, so a partially installed interceptor never leaves
            // installCount positive (cleanUp would otherwise uninstall the
            // shared proxy for every later compile).
            THIS_INTERCEPTOR.set(this);
            installed = true;
            sharedInstallCount++;
        }
    }

    private static InvocationHandler getSharedInvocationHandler() {
        return (proxy, method, args) -> {
            Object result;
            try {
                result = method.invoke(sharedMavenPluginManager, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }

            if (isCompilerMojoGetConfiguredMojoCall(method, result)) {
                MavenCompilerLogInterceptor thisInterceptor = THIS_INTERCEPTOR.get();
                if (thisInterceptor != null && result != null) {
                    thisInterceptor.mojo = (Mojo) result;
                    Log oldMojoLog = thisInterceptor.mojo.getLog();
                    if (oldMojoLog != null) {
                        thisInterceptor.oldMojoLog = oldMojoLog;
                        thisInterceptor.mojo.setLog(new FilteringLog(thisInterceptor.oldMojoLog));
                    }
                }
            }
            return result;
        };
    }

    private static boolean isCompilerMojoGetConfiguredMojoCall(Method method, Object result) {
        return method.getName().equals("getConfiguredMojo") && result instanceof Mojo
                && (result.getClass().getName().equals(COMPILER_MOJO)
                || result.getClass().getName().equals(TEST_COMPILER_MOJO));
    }

    @SuppressWarnings("all")
    private static void set(Field field, Object obj, Object value) throws IllegalAccessException {
        ensureAccessible(field);
        field.set(obj, value);
    }

    @SuppressWarnings("all")
    private static void ensureAccessible(Field field) {
        if (!field.isAccessible()) {
            field.setAccessible(true);
        }
    }

    public void cleanUp() {
        THIS_INTERCEPTOR.remove();
        if (mojo != null && oldMojoLog != null) {
            Failable.run(() -> mojo.setLog(oldMojoLog),
                    interceptorLog::debug);
        }

        synchronized (SHARED_INSTALL_LOCK) {
            if (!installed) {
                return;
            }
            installed = false;
            --sharedInstallCount;
            if (sharedInstallCount == 0 && sharedField != null && sharedMavenPluginManager != null) {
                Failable.run(() -> set(sharedField, sharedBuildPluginManager, sharedMavenPluginManager),
                        interceptorLog::debug);
                sharedField = null;
                sharedMavenPluginManager = null;
                sharedBuildPluginManager = null;
            }
        }
    }

    private static final class FilteringLog implements Log {

        private final Log delegate;

        private FilteringLog(Log delegate) {
            this.delegate = delegate;
        }

        private boolean isAllowed(CharSequence message) {
            return message == null
                    || (!message.toString().contains("value classes are a preview feature")
                    && !message.toString().startsWith("Overwriting artifact's file from "));
        }

        public boolean isDebugEnabled() {
            return delegate.isDebugEnabled();
        }

        public void debug(CharSequence content) {
            delegate.debug(content);
        }

        public void debug(CharSequence content, Throwable error) {
            delegate.debug(content, error);
        }

        public void debug(Throwable error) {
            delegate.debug(error);
        }

        public boolean isInfoEnabled() {
            return delegate.isInfoEnabled();
        }

        public void info(CharSequence content) {
            delegate.info(content);
        }

        public void info(CharSequence content, Throwable error) {
            delegate.info(content, error);
        }

        public void info(Throwable error) {
            delegate.info(error);
        }

        public boolean isWarnEnabled() {
            return delegate.isWarnEnabled();
        }

        public void warn(CharSequence content) {
            if (isAllowed(content)) {
                delegate.warn(content);
            }
        }

        public void warn(CharSequence content, Throwable error) {
            if (isAllowed(content)) {
                delegate.warn(content, error);
            }
        }

        public void warn(Throwable error) {
            delegate.warn(error);
        }

        public boolean isErrorEnabled() {
            return delegate.isErrorEnabled();
        }

        public void error(CharSequence content) {
            delegate.error(content);
        }

        public void error(CharSequence content, Throwable error) {
            delegate.error(content, error);
        }

        public void error(Throwable error) {
            delegate.error(error);
        }
    }
}
