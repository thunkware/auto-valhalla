package io.github.thunkware.auto.valhalla.maven;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.logging.Log;

final class MavenCompilerLogInterceptor {

    private final Log interceptorLog;
    private BuildPluginManager buildPluginManager;
    private Field field;
    private MavenPluginManager oldMavenPluginManager;
    private Mojo mojo;
    private Log oldMojoLog;

    MavenCompilerLogInterceptor(Log log) {
        this.interceptorLog = log;
    }

    synchronized void installLogInterceptor(BuildPluginManager buildPluginManager) {
        try {
            this.buildPluginManager = buildPluginManager;
            field = buildPluginManager.getClass().getDeclaredField("mavenPluginManager");
            field.setAccessible(true);
            oldMavenPluginManager = (MavenPluginManager) field.get(buildPluginManager);

            InvocationHandler handler = (proxy, method, args) -> {
                Object result;
                try {
                    result = method.invoke(oldMavenPluginManager, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
                if (method.getName().equals("getConfiguredMojo") && result instanceof Mojo
                        && result.getClass().getName().equals("org.apache.maven.plugin.compiler.CompilerMojo")) {

                    mojo = (Mojo) result;
                    oldMojoLog = mojo.getLog();
                    if (oldMojoLog != null) {
                        mojo.setLog(new FilteringLog(oldMojoLog));
                    }
                }
                return result;
            };
            Class<?>[] ifaces = {MavenPluginManager.class};
            MavenPluginManager newMavenPluginManager = (MavenPluginManager) Proxy.newProxyInstance(MavenPluginManager.class.getClassLoader(), ifaces, handler);
            field.set(buildPluginManager, newMavenPluginManager);
        } catch (Exception e) {
            interceptorLog.debug(e);
        }
    }

    public void cleanUp() {
        try {
            if (field != null && oldMavenPluginManager != null) {
                field.set(buildPluginManager, oldMavenPluginManager);
                if (mojo != null && oldMojoLog != null) {
                    mojo.setLog(oldMojoLog);
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
