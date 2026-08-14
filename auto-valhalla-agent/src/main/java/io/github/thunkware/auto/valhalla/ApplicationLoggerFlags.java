package io.github.thunkware.auto.valhalla;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordination flags for the SLF4J application-logger bridge, used by
 * {@link ApplicationLoggerBridgeTransformer} to install the bridge at the
 * right moment during application startup.
 *
 * <p>By default (non-Spring), the bridge fires when
 * {@link #onLoggerFactoryReady()} is called — i.e. when
 * {@code org.slf4j.LoggerFactory.getILoggerFactory()} exits, meaning SLF4J is
 * initialised. If a Spring Boot application is detected first (via the
 * {@code SpringApplication} static initializer), the target switches to
 * {@link #onSpringLoggingReady()}, which fires after
 * {@code LoggingApplicationListener.initialize()} — the point at which the
 * backing logging library (Logback / Log4j2) is actually configured.
 *
 * <p>All three public methods are injected as {@code INVOKESTATIC} calls by
 * {@link ApplicationLoggerBridgeTransformer} and must remain public.
 */
public final class ApplicationLoggerFlags {

    private static final AtomicBoolean bridgeLoggerFactory    = new AtomicBoolean(false);
    private static final AtomicBoolean bridgeSpringBootLogging = new AtomicBoolean(false);

    private ApplicationLoggerFlags() {}

    /** Called from {@link AutoValhallaAgent} when {@code logging=application} is set. */
    static void enableApplicationMode() {
        bridgeLoggerFactory.set(true);
    }

    /**
     * Injected at the exit of {@code org.slf4j.LoggerFactory.getILoggerFactory()}.
     * Installs the SLF4J bridge if this is not a Spring Boot application (Spring
     * Boot switches to the {@link #onSpringLoggingReady()} path instead).
     */
    public static void onLoggerFactoryReady() {
        if (bridgeLoggerFactory.compareAndSet(true, false)) {
            InternalLogger.Slf4jBridge.reinstall();
        }
    }

    /**
     * Injected at the entry of {@code org.springframework.boot.SpringApplication}
     * static initializer. Disables the {@code LoggerFactory} path and switches to
     * waiting for Spring Boot's {@code LoggingApplicationListener} instead, because
     * SLF4J alone is ready before Spring Boot configures the backing logging library.
     */
    public static void setSpringBootApp() {
        bridgeLoggerFactory.set(false);
        bridgeSpringBootLogging.set(true);
    }

    /**
     * Injected at the exit of {@code LoggingApplicationListener.initialize()}.
     * At this point the backing logging library (Logback / Log4j2) is configured
     * and the SLF4J bridge can be installed safely.
     */
    public static void onSpringLoggingReady() {
        if (bridgeSpringBootLogging.compareAndSet(true, false)) {
            InternalLogger.Slf4jBridge.reinstall();
        }
    }
}
