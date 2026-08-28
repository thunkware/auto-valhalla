package io.github.thunkware.auto.valhalla.maven.compiler;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.logging.Log;

final class MavenCompilerLogInterceptor {

    private static final Object INSTALL_LOCK = new Object();
    private static int installCount = 0;
    private static Field sharedField;
    private static BuildPluginManager sharedBpm;
    private static MavenPluginManager originalManager;
    private static final ThreadLocal<MavenCompilerLogInterceptor> ACTIVE = new ThreadLocal<>();

    private final Log interceptorLog;
    private Mojo mojo;
    private Log oldMojoLog;

    MavenCompilerLogInterceptor(Log log) {
        this.interceptorLog = log;
    }

    void installLogInterceptor(BuildPluginManager buildPluginManager) {
        try {
            synchronized (INSTALL_LOCK) {
                if (installCount++ == 0) {
                    sharedBpm = buildPluginManager;
                    sharedField = buildPluginManager.getClass().getDeclaredField("mavenPluginManager");
                    sharedField.setAccessible(true);
                    originalManager = (MavenPluginManager) sharedField.get(buildPluginManager);

                    InvocationHandler handler = (proxy, method, args) -> {
                        Object result;
                        try {
                            result = method.invoke(originalManager, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        if (method.getName().equals("getConfiguredMojo") && result instanceof Mojo
                                && result.getClass().getName().equals("org.apache.maven.plugin.compiler.CompilerMojo")) {

                            MavenCompilerLogInterceptor active = ACTIVE.get();
                            if (active != null) {
                                active.mojo = (Mojo) result;
                                Log oldMojoLog = active.mojo.getLog();
                                if (oldMojoLog != null && !(oldMojoLog instanceof FilteringLog)) {
                                    active.oldMojoLog = oldMojoLog;
                                    active.mojo.setLog(new FilteringLog(active.oldMojoLog));
                                }
                            }
                        }
                        return result;
                    };
                    Class<?>[] ifaces = {MavenPluginManager.class};
                    MavenPluginManager newMavenPluginManager = (MavenPluginManager) Proxy.newProxyInstance(MavenPluginManager.class.getClassLoader(), ifaces, handler);
                    sharedField.set(buildPluginManager, newMavenPluginManager);
                }
                ACTIVE.set(this);
            }
        } catch (Exception e) {
            interceptorLog.debug(e);
        }
    }

    public void cleanUp() {
        ACTIVE.remove();
        try {
            if (mojo != null && oldMojoLog != null) {
                mojo.setLog(oldMojoLog);
            }
            synchronized (INSTALL_LOCK) {
                if (--installCount == 0 && sharedField != null && originalManager != null) {
                    sharedField.set(sharedBpm, originalManager);
                    sharedField = null;
                    originalManager = null;
                    sharedBpm = null;
                }
            }
        } catch (Exception e) {
            interceptorLog.debug(e);
        }
    }

    private static final class FilteringLog implements Log {

        private final Log delegate;

        private FilteringLog(Log delegate) {
            this.delegate = delegate;
        }

        private boolean suppressed(CharSequence message) {
            return message != null
                    && message.toString().startsWith("Overwriting artifact's file from ");
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
            if (!suppressed(content)) {
                delegate.warn(content);
            }
        }

        public void warn(CharSequence content, Throwable error) {
            if (!suppressed(content)) {
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
