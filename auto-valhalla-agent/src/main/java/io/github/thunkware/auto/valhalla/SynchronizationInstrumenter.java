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
import java.util.List;

/**
 * Instruments {@code monitorenter} instructions in a class to call
 * {@link SynchronizationMonitor#check(Object)}, which records the class being
 * synchronized on for monitoring.
 *
 * <p>This is applied when {@code Mode.SYNCHRONIZATION_MONITOR} is set and
 * {@code auto-valhalla.synchronization-monitor.append-to} is configured.
 */
final class SynchronizationInstrumenter {

    private SynchronizationInstrumenter() {}

    /** Returns true if any method in the class contains a {@code monitorenter}. */
    static boolean hasMonitorEnter(ClassModel model) {
        return model.methods().stream()
                .anyMatch(m -> m.code().map(c -> c.elementList().stream()
                        .anyMatch(e -> e instanceof MonitorInstruction mi
                                && mi.opcode() == Opcode.MONITORENTER))
                        .orElse(false));
    }

    /**
     * Instruments all {@code monitorenter} instructions in the model by
     * inserting a {@link SynchronizationMonitor#check(Object)} call before each one.
     * Returns {@code null} when stack-map regeneration produces invalid frames
     * (e.g. the class references types absent from the system classloader); the
     * caller is responsible for failure handling. Callers should check
     * {@link #hasMonitorEnter} first to distinguish "nothing to instrument" from
     * an instrumentation failure.
     */
    static byte[] instrument(ClassModel model) {
        CodeTransform guard = (cb, e) -> {
            if (e instanceof MonitorInstruction mi && mi.opcode() == Opcode.MONITORENTER) {
                cb.dup();
                cb.invokestatic(ClassDesc.of("io.github.thunkware.auto.valhalla.SynchronizationMonitor"),
                        "check", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
            }
            cb.accept(e);
        };
        ClassFile cf = ClassFiles.of();
        byte[] out = cf.transformClass(model, ClassTransform.transformingMethodBodies(guard));
        // Stack-map regeneration can produce incorrect frames when the class
        // references types not in the system classloader (e.g. H2, Spring types).
        List<VerifyError> errors = cf.verify(out);
        return errors.isEmpty() ? out : null;
    }
}
