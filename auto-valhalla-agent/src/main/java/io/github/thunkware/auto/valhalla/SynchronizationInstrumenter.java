package io.github.thunkware.auto.valhalla;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

/**
 * Instruments {@code monitorenter} instructions in a class to call
 * {@link SynchronizationInspector#check(Object)}, which records the class being
 * synchronized on for monitoring.
 *
 * <p>This is applied when {@code Mode.SYNCHRONIZATION_MONITOR} is set and
 * {@code auto-valhalla.synchronization-monitor.append-to} is configured.
 */
final class SynchronizationInstrumenter {

    private SynchronizationInstrumenter() {}

    /**
     * Instruments all {@code monitorenter} instructions in the model by
     * inserting a {@link SynchronizationInspector#check(Object)} call before each one.
     * Returns {@code null} if the class has no {@code monitorenter}, so
     * unrelated classes are left untouched.
     */
    static byte[] instrument(ClassModel model) {
        boolean hasMonitor = model.methods().stream()
                .anyMatch(m -> m.code().map(c -> c.elementList().stream()
                        .anyMatch(e -> e instanceof MonitorInstruction mi
                                && mi.opcode() == Opcode.MONITORENTER))
                        .orElse(false));
        if (!hasMonitor) {
            return null;
        }
        CodeTransform guard = (cb, e) -> {
            if (e instanceof MonitorInstruction mi && mi.opcode() == Opcode.MONITORENTER) {
                cb.dup();
                cb.invokestatic(ClassDesc.of("io.github.thunkware.auto.valhalla.SynchronizationInspector"),
                        "check", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
            }
            cb.accept(e);
        };
        return ClassFile.of().transformClass(model,
                ClassTransform.transformingMethodBodies(guard));
    }
}
