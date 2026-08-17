package io.github.thunkware.auto.valhalla.logger;

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

    /** Called from {@code AutoValhallaAgent28} when {@code logging=application} is set. */
    public static void enableApplicationLoggingSystem() {
        bridgeLoggerFactory.set(true);
    }

    /**
     * Injected at the entry of {@code org.slf4j.LoggerFactory.getILoggerFactory()}.
     * Starts buffering log output so messages emitted during SLF4J initialisation
     * are not lost. Ignored if this is a Spring Boot app (handled by
     * {@link #onSpringLoggingInitializing()} instead).
     */
    public static void onLoggerFactoryInitializing() {
        if (bridgeLoggerFactory.get()) {
            InternalLoggerFactory.startBuffering();
        }
    }

    /**
     * Injected at the exit of {@code org.slf4j.LoggerFactory.getILoggerFactory()}.
     * Installs the SLF4J bridge if this is not a Spring Boot application (Spring
     * Boot switches to the {@link #onSpringLoggingReady()} path instead).
     */
    public static void onLoggerFactoryReady() {
        if (bridgeLoggerFactory.compareAndSet(true, false)) {
            InternalLoggerFactory.reinstall();
        }
    }

    /**
     * Called when any class from {@code org.springframework.boot.loader.launch}
     * is loaded. That package is the Spring Boot fat-jar launcher, so its presence
     * means we are running inside a Spring Boot executable jar. Start buffering
     * immediately — earlier than waiting for {@code SpringApplication.<clinit>} —
     * so no agent messages are lost before {@code LoggingApplicationListener}
     * finishes configuring the logging framework.
     */
    public static void onSpringBootLauncherSeen() {
        bridgeLoggerFactory.set(false);
        bridgeSpringBootLogging.set(true);
        InternalLoggerFactory.startBuffering();
    }

    /**
     * Injected at the entry of {@code org.springframework.boot.SpringApplication}
     * static initializer. Disables the {@code LoggerFactory} path and switches to
     * waiting for Spring Boot's {@code LoggingApplicationListener} instead, because
     * SLF4J alone is ready before Spring Boot configures the backing logging library.
     * Also starts buffering in case the launcher package was not on the classpath
     * (e.g. exploded-jar / IDE run).
     */
    public static void setSpringBootApp() {
        bridgeLoggerFactory.set(false);
        bridgeSpringBootLogging.set(true);
        InternalLoggerFactory.startBuffering();
    }

    /**
     * Injected at the entry of {@code LoggingApplicationListener.initialize()}.
     * Logging is buffered in memory from this point until
     * {@link #onSpringLoggingReady()} flushes via SLF4J, so agent messages
     * emitted during Logback / Log4j2 configuration are not lost.
     */
    public static void onSpringLoggingInitializing() {
        if (bridgeSpringBootLogging.get()) {
            InternalLoggerFactory.startBuffering();
        }
    }

    /**
     * Injected at the exit of {@code LoggingApplicationListener.initialize()}.
     * At this point the backing logging library (Logback / Log4j2) is configured;
     * installs the SLF4J bridge and flushes any messages buffered during init.
     */
    public static void onSpringLoggingReady() {
        if (bridgeSpringBootLogging.compareAndSet(true, false)) {
            InternalLoggerFactory.reinstall();
        }
    }
}
