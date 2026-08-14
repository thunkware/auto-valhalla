package io.github.thunkware.auto.valhalla.logger;

import io.github.thunkware.auto.valhalla.ClassFiles;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;

/**
 * Instruments SLF4J and Spring Boot classes to detect when the application's
 * logging system is ready, then installs the SLF4J bridge for
 * {@code auto-valhalla.logging=application} mode.
 *
 * <p>Three injection points, mirroring OTel's {@code internal-application-logger}
 * module:
 * <ul>
 *   <li>{@code org.slf4j.LoggerFactory.getILoggerFactory()} exit →
 *       {@link ApplicationLoggerFlags#onLoggerFactoryReady()} for non-Spring-Boot
 *       apps (SLF4J is ready once this method returns).</li>
 *   <li>{@code org.springframework.boot.SpringApplication} type initializer
 *       entry → {@link ApplicationLoggerFlags#setSpringBootApp()}, switching to
 *       the Spring-aware install path (SLF4J alone is ready too early in Spring
 *       Boot; we must wait for the logging subsystem to be configured).</li>
 *   <li>{@code LoggingApplicationListener.initialize()} exit (Spring Boot 1.x
 *       and 2.x packages) → {@link ApplicationLoggerFlags#onSpringLoggingReady()}
 *       once the backing library (Logback / Log4j2) is configured.</li>
 * </ul>
 *
 * <p>Only registered by {@code AutoValhallaAgent} when
 * {@code auto-valhalla.logging=application} is configured.
 */
public final class ApplicationLoggerBridgeTransformer implements ClassFileTransformer {

    private final InternalLogger log = InternalLogger.getLogger(ApplicationLoggerBridgeTransformer.class);

    private static final String SLF4J_LOGGER_FACTORY   = "org/slf4j/LoggerFactory";
    private static final String SPRING_APPLICATION      = "org/springframework/boot/SpringApplication";
    private static final String SPRING_LISTENER_1X      =
            "org/springframework/boot/logging/LoggingApplicationListener";
    private static final String SPRING_LISTENER_2X      =
            "org/springframework/boot/context/logging/LoggingApplicationListener";
    private static final String SPRING_BOOT_LAUNCHER_PKG = "org/springframework/boot/loader/launch/";

    private static final ClassDesc FLAGS_CLASS =
            ClassDesc.of("io.github.thunkware.auto.valhalla.logger.ApplicationLoggerFlags");
    private static final MethodTypeDesc VOID_DESC = MethodTypeDesc.of(ConstantDescs.CD_void);

    @Override
    public byte[] transform(Module module, ClassLoader loader, String classNameJvm,
            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (classNameJvm == null) return null;
        if (classNameJvm.startsWith(SPRING_BOOT_LAUNCHER_PKG)) {
            ApplicationLoggerFlags.onSpringBootLauncherSeen();
            return null;
        }
        return switch (classNameJvm) {
            case SLF4J_LOGGER_FACTORY -> {
                // Inject entry first (start buffering), then exit (flush via SLF4J).
                byte[] withEntry = transformClass(classfileBuffer, loader,
                        "getILoggerFactory", "onLoggerFactoryInitializing", false);
                yield transformClass(
                        withEntry != null ? withEntry : classfileBuffer, loader,
                        "getILoggerFactory", "onLoggerFactoryReady", true);
            }
            case SPRING_APPLICATION ->
                transformClass(classfileBuffer, loader,
                        "<clinit>", "setSpringBootApp", false);
            case SPRING_LISTENER_1X, SPRING_LISTENER_2X -> {
                // Inject entry first (start buffering), then exit (flush via SLF4J).
                byte[] withEntry = transformClass(classfileBuffer, loader,
                        "initialize", "onSpringLoggingInitializing", false);
                yield transformClass(
                        withEntry != null ? withEntry : classfileBuffer, loader,
                        "initialize", "onSpringLoggingReady", true);
            }
            default -> null;
        };
    }

    /**
     * Locates {@code methodName} in the class, applies the appropriate injection
     * transform, verifies the result, and returns the new bytecode; or {@code null}
     * on verify failure or any exception.
     *
     * @param atExit {@code true} → inject an {@code INVOKESTATIC} call before
     *               every return instruction (method exit); {@code false} → inject
     *               before the first code instruction (method entry).
     */
    private byte[] transformClass(byte[] classfileBuffer, ClassLoader loader,
            String methodName, String flagsMethod, boolean atExit) {
        try {
            ClassFile cf = ClassFiles.of(loader);
            ClassModel model = cf.parse(classfileBuffer);
            ClassTransform classTransform = (classBuilder, classElement) -> {
                if (classElement instanceof MethodModel method
                        && method.methodName().equalsString(methodName)
                        && method.code().isPresent()) {
                    MethodTransform mt = atExit
                            ? MethodTransform.transformingCode(exitCodeTransform(flagsMethod))
                            : entryMethodTransform(flagsMethod);
                    classBuilder.transformMethod(method, mt);
                } else {
                    classBuilder.with(classElement);
                }
            };
            byte[] out = cf.transformClass(model, classTransform);
            List<java.lang.VerifyError> errors = cf.verify(out);
            if (!errors.isEmpty()) {
                log.debug("application-logger bridge: verify failed for "
                        + methodName + " in " + model.thisClass().asInternalName()
                        + ": " + errors.get(0).getMessage());
                return null;
            }
            return out;
        } catch (Exception e) {
            log.debug("application-logger bridge: failed to instrument "
                    + methodName + ": " + e);
            return null;
        }
    }

    /** Injects {@code INVOKESTATIC flagsMethod()} before every {@link ReturnInstruction}. */
    private static CodeTransform exitCodeTransform(String flagsMethod) {
        return (cb, e) -> {
            if (e instanceof ReturnInstruction) {
                cb.invokestatic(FLAGS_CLASS, flagsMethod, VOID_DESC);
            }
            cb.with(e);
        };
    }

    /**
     * Injects {@code INVOKESTATIC flagsMethod()} before the first real code
     * instruction (method entry). Uses a stateful per-method transform so the
     * injection fires exactly once regardless of how many instructions follow.
     */
    private static MethodTransform entryMethodTransform(String flagsMethod) {
        return (methodBuilder, methodElement) -> {
            if (methodElement instanceof CodeModel code) {
                boolean[] injected = {false};
                methodBuilder.transformCode(code, (cb, e) -> {
                    if (!injected[0] && e instanceof Instruction) {
                        cb.invokestatic(FLAGS_CLASS, flagsMethod, VOID_DESC);
                        injected[0] = true;
                    }
                    cb.with(e);
                });
            } else {
                methodBuilder.with(methodElement);
            }
        };
    }
}
