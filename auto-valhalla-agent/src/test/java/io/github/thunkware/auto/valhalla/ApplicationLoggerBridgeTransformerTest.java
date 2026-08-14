package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thunkware.auto.valhalla.logger.ApplicationLoggerBridgeTransformer;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import org.junit.jupiter.api.Test;

class ApplicationLoggerBridgeTransformerTest {

    private static final ApplicationLoggerBridgeTransformer TRANSFORMER =
            new ApplicationLoggerBridgeTransformer();

    /**
     * Builds minimal class bytes whose internal name is {@code internalName} and
     * which contains exactly one method named {@code methodName} that does nothing
     * (just returns void). Uses {@code ACC_STATIC} for {@code <clinit>}.
     */
    private static byte[] buildClass(String internalName, String methodName) {
        ClassFile cf = ClassFile.of();
        ClassDesc owner = ClassDesc.ofDescriptor("L" + internalName + ";");
        int flags = "<clinit>".equals(methodName)
                ? AccessFlag.STATIC.mask()
                : AccessFlag.PUBLIC.mask();
        return cf.build(owner, classBuilder ->
            classBuilder.withMethod(methodName,
                    MethodTypeDesc.of(ConstantDescs.CD_void), flags,
                    mb -> mb.withCode(code -> code.return_())));
    }

    private static byte[] transform(String internalName, byte[] bytes) {
        return TRANSFORMER.transform(null, null, internalName, null, null, bytes);
    }

    private static boolean containsCallTo(byte[] bytes, String methodName) {
        ClassModel model = ClassFile.of().parse(bytes);
        return model.methods().stream()
                .anyMatch(m -> m.code().map(code -> code.elementList().stream()
                        .anyMatch(e -> e instanceof InvokeInstruction ii
                                && ii.opcode() == Opcode.INVOKESTATIC
                                && ii.name().equalsString(methodName)))
                        .orElse(false));
    }

    @Test
    void slf4jLoggerFactoryGetILoggerFactoryIsInstrumented() {
        byte[] out = transform("org/slf4j/LoggerFactory",
                buildClass("org/slf4j/LoggerFactory", "getILoggerFactory"));
        assertNotNull(out, "LoggerFactory must be instrumented");
        assertTrue(containsCallTo(out, "onLoggerFactoryReady"),
                "output must call ApplicationLoggerFlags.onLoggerFactoryReady");
        assertTrue(ClassFile.of().verify(out).isEmpty(), "output must pass bytecode verification");
    }

    @Test
    void springApplicationClinitIsInstrumented() {
        byte[] out = transform("org/springframework/boot/SpringApplication",
                buildClass("org/springframework/boot/SpringApplication", "<clinit>"));
        assertNotNull(out, "SpringApplication must be instrumented");
        assertTrue(containsCallTo(out, "setSpringBootApp"),
                "output must call ApplicationLoggerFlags.setSpringBootApp");
        assertTrue(ClassFile.of().verify(out).isEmpty(), "output must pass bytecode verification");
    }

    @Test
    void springListener2xInitializeIsInstrumented() {
        String name = "org/springframework/boot/context/logging/LoggingApplicationListener";
        byte[] out = transform(name, buildClass(name, "initialize"));
        assertNotNull(out, "Spring Boot 2.x LoggingApplicationListener must be instrumented");
        assertTrue(containsCallTo(out, "onSpringLoggingReady"),
                "output must call ApplicationLoggerFlags.onSpringLoggingReady");
        assertTrue(ClassFile.of().verify(out).isEmpty(), "output must pass bytecode verification");
    }

    @Test
    void springListener1xInitializeIsInstrumented() {
        String name = "org/springframework/boot/logging/LoggingApplicationListener";
        byte[] out = transform(name, buildClass(name, "initialize"));
        assertNotNull(out, "Spring Boot 1.x LoggingApplicationListener must be instrumented");
        assertTrue(containsCallTo(out, "onSpringLoggingReady"),
                "output must call ApplicationLoggerFlags.onSpringLoggingReady");
    }

    @Test
    void unrelatedClassIsNotTransformed() {
        byte[] original = buildClass("com/example/SomeClass", "someMethod");
        assertNull(transform("com/example/SomeClass", original),
                "unrelated class must not be transformed");
    }

    @Test
    void nullClassNameIsNotTransformed() {
        byte[] original = buildClass("com/example/SomeClass", "someMethod");
        assertNull(transform(null, original), "null class name must not be transformed");
    }

    @Test
    void classWithoutTargetMethodIsNotModified() {
        // LoggerFactory class, but no getILoggerFactory method — transform returns non-null
        // (the class is parsed and rebuilt) but without the injected call.
        byte[] original = buildClass("org/slf4j/LoggerFactory", "someOtherMethod");
        byte[] out = transform("org/slf4j/LoggerFactory", original);
        // The class IS rebuilt (it matches by class name) but no injection target found
        // so the output contains no call to onLoggerFactoryReady.
        if (out != null) {
            assertTrue(!containsCallTo(out, "onLoggerFactoryReady"),
                    "no injection when target method is absent");
        }
    }
}
