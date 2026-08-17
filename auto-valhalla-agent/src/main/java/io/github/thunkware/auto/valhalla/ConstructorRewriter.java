package io.github.thunkware.auto.valhalla;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rewrites constructors so that all instance-field stores happen in the early
 * construction phase (before the {@code super()}/{@code this()} call), as
 * required by JEP 539 (Strict Field Initialization in the JVM) for value
 * classes.
 *
 * <p>A class compiled as an ordinary identity class has constructors that call
 * {@code super()} first and then assign fields. For a value class the JVM
 * requires the opposite: every strict instance field must be assigned before
 * {@code super()} is invoked, otherwise verification fails. This class performs
 * that reordering by analyzing the operand-stack effect of each instruction and
 * relocating the field-init sequences to the front of the constructor.
 *
 * <p>Within the relocated (early) phase {@code this} is not yet initialized, so
 * reading a field with {@code getfield} on {@code this} is rejected by the
 * verifier. To avoid that, every own-field store is also captured into a fresh
 * local variable; subsequent reads of that same field in the early phase are
 * rewritten to load the captured local instead of {@code getfield this.field}.
 * This mirrors what {@code javac} itself does when generating a value-class
 * constructor.
 *
 * <p>When a constructor cannot be made strictly initialized (for example, a
 * field initializer invokes an instance method on {@code this}, or a read of an
 * own field happens before that field has been stored in the early phase), the
 * shared {@code failed} flag is set and the whole class is left untouched.
 */
final class ConstructorRewriter {

    private static final int THIS = 0;
    private static final int OTHER = 1;
    private static final int SAFE = 0;
    private static final int PUTFIELD_OWN = 1;
    private static final int UNSAFE = 2;

    private ConstructorRewriter() {}

    /** Transform to apply to every {@code <init>} method body. */
    static ClassTransform transformConstructors(ClassModel owner, AtomicBoolean failed) {
        return ClassTransform.transformingMethodBodies(
            mm -> mm.methodName().stringValue().equals("<init>"),
            CodeTransform.ofStateful(() -> new Fixer(owner, failed)));
    }

    private static final class Fixer implements CodeTransform {

        private final ClassModel owner;
        private final AtomicBoolean failed;
        private final List<CodeElement> elems = new ArrayList<>();
        private final Map<String, TypeKind> fieldKinds = new HashMap<>();

        Fixer(ClassModel owner, AtomicBoolean failed) {
            this.owner = owner;
            this.failed = failed;
            for (FieldModel fm : owner.fields()) {
                fieldKinds.put(fm.fieldName().stringValue(), TypeKind.from(fm.fieldTypeSymbol()));
            }
        }

        @Override
        public void accept(CodeBuilder cb, CodeElement e) {
            elems.add(e);
        }

        @Override
        public void atEnd(CodeBuilder cb) {
            int s = superCallIndex();
            if (s < 0) {
                elems.forEach(cb);
                return;
            }
            Result r = reorder(s);
            if (r.failed) {
                failed.set(true);
                elems.forEach(cb);
                return;
            }
            if (!r.changed) {
                elems.forEach(cb);
                return;
            }
            if (!satisfiable(r.movable)) {
                failed.set(true);
                elems.forEach(cb);
                return;
            }

            if (r.movable.isEmpty()) {
                // No own field initializers to relocate. A value class must not
                // invoke super(), so drop the prologue (the leading this-load and
                // the invokespecial Object.<init>) entirely; the remaining body is
                // valid on its own (typically just a return).
                r.late.forEach(cb);
                return;
            }

            r.prefix.forEach(cb);
            Map<String, Integer> fieldLocal = new HashMap<>();
            String self = owner.thisClass().asInternalName();
            for (int k = 0; k < r.movable.size(); k++) {
                CodeElement e = r.movable.get(k);
                if (isThisLoad(e)) {
                    CodeElement nxt = (k + 1 < r.movable.size()) ? r.movable.get(k + 1) : null;
                    if (nxt instanceof FieldInstruction fi && fi.opcode() == Opcode.GETFIELD
                            && fi.owner().asInternalName().equals(self)
                            && fieldLocal.containsKey(fi.name().stringValue())) {
                        continue;
                    }
                }
                emitEarly(cb, e, self, fieldLocal);
            }
            cb.accept(r.superElem);
            r.late.forEach(cb);
        }

        /**
         * Emits an early-phase element, substituting own-field reads with the
         * captured local and capturing own-field stores into a fresh local.
         */
        private void emitEarly(CodeBuilder cb, CodeElement e, String self, Map<String, Integer> fieldLocal) {
            if (e instanceof FieldInstruction fi && fi.owner().asInternalName().equals(self)) {
                String name = fi.name().stringValue();
                if (fi.opcode() == Opcode.GETFIELD) {
                    Integer local = fieldLocal.get(name);
                    if (local != null) {
                        cb.loadLocal(fieldKinds.get(name), local);
                        return;
                    }
                } else if (fi.opcode() == Opcode.PUTFIELD) {
                    TypeKind kind = fieldKinds.get(name);
                    if (kind != null) {
                        int local = cb.allocateLocal(kind);
                        if (kind.slotSize() == 2) {
                            cb.dup2();
                        } else {
                            cb.dup();
                        }
                        cb.storeLocal(kind, local);
                        fieldLocal.put(name, local);
                        cb.accept(e);
                        return;
                    }
                }
            }
            cb.accept(e);
        }

        /** True if every own-field read in the early phase has a prior store. */
        private boolean satisfiable(List<CodeElement> movable) {
            String self = owner.thisClass().asInternalName();
            Set<String> stored = new HashSet<>();
            for (CodeElement e : movable) {
                if (e instanceof FieldInstruction fi && fi.owner().asInternalName().equals(self)) {
                    if (fi.opcode() == Opcode.PUTFIELD) {
                        stored.add(fi.name().stringValue());
                    } else if (fi.opcode() == Opcode.GETFIELD
                            && !stored.contains(fi.name().stringValue())) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** True if {@code e} loads {@code this} ({@code aload_0}). */
        private static boolean isThisLoad(CodeElement e) {
            return e instanceof LoadInstruction li
                    && li.slot() == 0
                    && li.typeKind() == TypeKind.REFERENCE;
        }

        /**
         * @return relocated elements, or {@code null}/{@code false} when no
         *         relocation is needed
         */
        private Result reorder(int s) {
            List<CodeElement> prefix = new ArrayList<>();
            for (CodeElement e : elems.subList(0, s)) {
                if (e instanceof LocalVariable) {
                    continue;
                }
                prefix.add(e);
            }
            List<CodeElement> post = new ArrayList<>(elems.subList(s + 1, elems.size()));

            List<CodeElement> movable = new ArrayList<>();
            List<CodeElement> late = new ArrayList<>();
            List<CodeElement> block = new ArrayList<>();
            List<Integer> stack = new ArrayList<>();

            int i = 0;
            for (; i < post.size(); i++) {
                CodeElement e = post.get(i);
                if (e instanceof LineNumber || e instanceof LocalVariable) {
                    // Line numbers are preserved but are not instructions: they
                    // must not stop the relocation scan.
                    continue;
                }
                if (!(e instanceof Instruction ins)) {
                    break;
                }
                int r = classify(ins, stack);
                if (r == PUTFIELD_OWN) {
                    block.add(e);
                    movable.addAll(block);
                    block.clear();
                } else if (r == UNSAFE) {
                    break;
                } else {
                    block.add(e);
                }
            }
            // Whatever is still buffered in `block` sits between the last
            // relocated own-field store and post.get(i), so it must be emitted
            // *before* the rest of the body — appending it after `late` would
            // move it past the method's return, silently dropping the side
            // effects of statements such as a trailing method call.
            late.addAll(block);
            for (; i < post.size(); i++) {
                CodeElement e = post.get(i);
                if (e instanceof LocalVariable) {
                    continue;
                }
                late.add(e);
            }

            if (hasOwnPutfield(late)) {
                return new Result(prefix, null, elems.get(s), late, true, false);
            }
            if (movable.isEmpty()) {
                return new Result(prefix, movable, elems.get(s), late, false, false);
            }
            return new Result(prefix, movable, elems.get(s), late, false, true);
        }

        private int superCallIndex() {
            for (int i = 0; i < elems.size(); i++) {
                CodeElement e = elems.get(i);
                if (e instanceof InvokeInstruction ii
                        && ii.name().stringValue().equals("<init>")
                        && ii.opcode() == Opcode.INVOKESPECIAL) {
                    return i;
                }
            }
            return -1;
        }

        private boolean hasOwnPutfield(List<CodeElement> elems) {
            String self = owner.thisClass().asInternalName();
            for (CodeElement e : elems) {
                if (e instanceof FieldInstruction fi && fi.opcode() == Opcode.PUTFIELD
                        && fi.owner().asInternalName().equals(self)) {
                    return true;
                }
            }
            return false;
        }

        private int classify(Instruction ins, List<Integer> stack) {
            if (ins instanceof ConstantInstruction ci) {
                pushSlots(stack, ci.typeKind().slotSize());
                return SAFE;
            }
            if (ins instanceof LoadInstruction li) {
                if (li.typeKind().slotSize() == 2) {
                    stack.add(OTHER);
                    stack.add(OTHER);
                } else {
                    stack.add(li.slot() == 0 ? THIS : OTHER);
                }
                return SAFE;
            }
            if (ins instanceof StoreInstruction si) {
                int slots = si.typeKind().slotSize();
                for (int i = 0; i < slots; i++) {
                    if (pop(stack) == THIS) {
                        return UNSAFE;
                    }
                }
                return SAFE;
            }
            if (ins instanceof StackInstruction sp) {
                return stackOp(sp.opcode(), stack);
            }
            if (ins instanceof FieldInstruction fi) {
                return fieldOp(fi, stack);
            }
            if (ins instanceof InvokeInstruction ii) {
                return invokeOp(ii, stack);
            }
            if (ins instanceof InvokeDynamicInstruction idi) {
                int argSlots = argsSlots(idi.typeSymbol());
                for (int i = 0; i < argSlots; i++) {
                    if (pop(stack) == THIS) {
                        return UNSAFE;
                    }
                }
                pushSlots(stack, descSlots(idi.typeSymbol().returnType()));
                return SAFE;
            }
            if (ins instanceof OperatorInstruction op) {
                return operator(op, stack);
            }
            if (ins instanceof ConvertInstruction ci) {
                return convert(ci, stack);
            }
            if (ins instanceof ArrayLoadInstruction) {
                if (popNoThis(stack) == -1 || popNoThis(stack) == -1) {
                    return UNSAFE;
                }
                stack.add(OTHER);
                return SAFE;
            }
            if (ins instanceof ArrayStoreInstruction) {
                if (popNoThis(stack) == -1 || popNoThis(stack) == -1 || pop(stack) == THIS) {
                    return UNSAFE;
                }
                return SAFE;
            }
            if (ins instanceof NewPrimitiveArrayInstruction || ins instanceof NewReferenceArrayInstruction
                    || ins instanceof NewMultiArrayInstruction) {
                if (ins instanceof NewMultiArrayInstruction && popNoThis(stack) == -1) {
                    return UNSAFE;
                }
                if (popNoThis(stack) == -1) {
                    return UNSAFE;
                }
                stack.add(OTHER);
                return SAFE;
            }
            if (ins instanceof NewObjectInstruction) {
                stack.add(OTHER);
                return SAFE;
            }
            if (ins instanceof TypeCheckInstruction) {
                if (popNoThis(stack) == -1) {
                    return UNSAFE;
                }
                stack.add(OTHER);
                return SAFE;
            }
            if (ins instanceof IncrementInstruction || ins instanceof NopInstruction) {
                return SAFE;
            }
            // return, throw, branches, switches, monitors, labels, try/catch
            return UNSAFE;
        }

        private int fieldOp(FieldInstruction fi, List<Integer> stack) {
            String self = owner.thisClass().asInternalName();
            boolean own = fi.owner().asInternalName().equals(self);
            int valueSlots = descSlots(fi.typeSymbol());
            switch (fi.opcode()) {
                case PUTFIELD -> {
                    for (int i = 0; i < valueSlots; i++) {
                        if (pop(stack) == -1) {
                            return UNSAFE;
                        }
                    }
                    int receiver = pop(stack);
                    if (receiver != THIS || !stack.isEmpty()) {
                        return UNSAFE;
                    }
                    return own ? PUTFIELD_OWN : UNSAFE;
                }
                case GETFIELD -> {
                    int receiver = pop(stack);
                    if (receiver == -1) {
                        return UNSAFE;
                    }
                    if (receiver == THIS && !own) {
                        return UNSAFE;
                    }
                    pushSlots(stack, valueSlots);
                    return SAFE;
                }
                case GETSTATIC -> {
                    pushSlots(stack, valueSlots);
                    return SAFE;
                }
                case PUTSTATIC -> {
                    for (int i = 0; i < valueSlots; i++) {
                        if (pop(stack) == THIS) {
                            return UNSAFE;
                        }
                    }
                    return SAFE;
                }
                default -> {
                    return UNSAFE;
                }
            }
        }

        private int invokeOp(InvokeInstruction ii, List<Integer> stack) {
            String name = ii.name().stringValue();
            int argSlots = argsSlots(ii.typeSymbol());
            if (ii.opcode() == Opcode.INVOKESTATIC) {
                for (int i = 0; i < argSlots; i++) {
                    if (pop(stack) == THIS) {
                        return UNSAFE;
                    }
                }
                pushSlots(stack, descSlots(ii.typeSymbol().returnType()));
                return SAFE;
            }
            if (name.equals("<init>")) {
                // constructor call on a freshly allocated object, e.g. this.x = new Foo().
                // The receiver is popped; the initialized object (pushed by `new`
                // and duplicated by `dup`) remains on the stack, so nothing is
                // pushed back.
                for (int i = 0; i < argSlots; i++) {
                    if (pop(stack) == THIS) {
                        return UNSAFE;
                    }
                }
                if (pop(stack) == THIS) {
                    return UNSAFE;
                }
                return SAFE;
            }
            for (int i = 0; i < argSlots; i++) {
                if (pop(stack) == THIS) {
                    return UNSAFE;
                }
            }
            if (pop(stack) == THIS) {
                return UNSAFE;
            }
            pushSlots(stack, descSlots(ii.typeSymbol().returnType()));
            return SAFE;
        }

        private int operator(OperatorInstruction op, List<Integer> stack) {
            switch (op.opcode()) {
                case ARRAYLENGTH -> {
                    if (popNoThis(stack) == -1) {
                        return UNSAFE;
                    }
                    stack.add(OTHER);
                    return SAFE;
                }
                case LCMP, DCMPL, DCMPG -> {
                    // pop two category-2 values (4 slots), push one int
                    for (int i = 0; i < 4; i++) {
                        if (popNoThis(stack) == -1) {
                            return UNSAFE;
                        }
                    }
                    stack.add(OTHER);
                    return SAFE;
                }
                default -> { /* handled below */ }
            }
            boolean unary = op.opcode() == Opcode.INEG || op.opcode() == Opcode.LNEG
                    || op.opcode() == Opcode.FNEG || op.opcode() == Opcode.DNEG;
            boolean shift = op.opcode() == Opcode.ISHL || op.opcode() == Opcode.LSHL
                    || op.opcode() == Opcode.ISHR || op.opcode() == Opcode.LSHR
                    || op.opcode() == Opcode.IUSHR || op.opcode() == Opcode.LUSHR;
            int k = op.typeKind().slotSize();
            if (unary) {
                for (int i = 0; i < k; i++) {
                    if (popNoThis(stack) == -1) {
                        return UNSAFE;
                    }
                }
            } else if (shift) {
                for (int i = 0; i < k; i++) {
                    if (popNoThis(stack) == -1) {
                        return UNSAFE;
                    }
                }
                if (popNoThis(stack) == -1) {
                    return UNSAFE;
                }
            } else {
                for (int i = 0; i < 2 * k; i++) {
                    if (popNoThis(stack) == -1) {
                        return UNSAFE;
                    }
                }
            }
            pushSlots(stack, k);
            return SAFE;
        }

        private int convert(ConvertInstruction ci, List<Integer> stack) {
            for (int i = 0; i < ci.fromType().slotSize(); i++) {
                if (popNoThis(stack) == -1) {
                    return UNSAFE;
                }
            }
            pushSlots(stack, ci.toType().slotSize());
            return SAFE;
        }

        private int stackOp(Opcode op, List<Integer> stack) {
            switch (op) {
                case POP -> { return popNoThis(stack) == -1 ? UNSAFE : SAFE; }
                case POP2 -> {
                    if (stack.size() >= 2) {
                        return (popNoThis(stack) == -1 || popNoThis(stack) == -1) ? UNSAFE : SAFE;
                    }
                    if (stack.size() == 1) {
                        return popNoThis(stack) == -1 ? UNSAFE : SAFE;
                    }
                    return UNSAFE;
                }
                case DUP -> {
                    int t = top(stack);
                    if (t == -1) {
                        return UNSAFE;
                    }
                    stack.add(t);
                    return SAFE;
                }
                case DUP_X1 -> {
                    if (stack.size() < 2) {
                        return UNSAFE;
                    }
                    int t = stack.removeLast();
                    int u = stack.removeLast();
                    stack.add(t);
                    stack.add(u);
                    stack.add(t);
                    return SAFE;
                }
                case DUP_X2 -> {
                    if (stack.size() < 3) {
                        return UNSAFE;
                    }
                    int t = stack.removeLast();
                    int u = stack.removeLast();
                    int v = stack.removeLast();
                    stack.add(t);
                    stack.add(v);
                    stack.add(u);
                    stack.add(t);
                    return SAFE;
                }
                case DUP2 -> {
                    if (stack.size() < 2) {
                        return UNSAFE;
                    }
                    stack.add(stack.get(stack.size() - 2));
                    stack.add(stack.get(stack.size() - 2));
                    return SAFE;
                }
                case DUP2_X1 -> {
                    if (stack.size() < 3) {
                        return UNSAFE;
                    }
                    int t = stack.removeLast();
                    int u = stack.removeLast();
                    int v = stack.removeLast();
                    stack.add(u);
                    stack.add(t);
                    stack.add(v);
                    stack.add(u);
                    stack.add(t);
                    return SAFE;
                }
                case DUP2_X2 -> {
                    if (stack.size() < 4) {
                        return UNSAFE;
                    }
                    int t = stack.removeLast();
                    int u = stack.removeLast();
                    int v = stack.removeLast();
                    int w = stack.removeLast();
                    stack.add(u);
                    stack.add(t);
                    stack.add(w);
                    stack.add(v);
                    stack.add(u);
                    stack.add(t);
                    return SAFE;
                }
                case SWAP -> {
                    if (stack.size() < 2) {
                        return UNSAFE;
                    }
                    Collections.swap(stack, stack.size() - 1, stack.size() - 2);
                    return SAFE;
                }
                default -> { return UNSAFE; }
            }
        }

        private static void pushSlots(List<Integer> stack, int slots) {
            for (int i = 0; i < slots; i++) {
                stack.add(OTHER);
            }
        }

        private int pop(List<Integer> stack) {
            return stack.isEmpty() ? -1 : stack.removeLast();
        }

        private int popNoThis(List<Integer> stack) {
            if (stack.isEmpty()) {
                return -1;
            }
            int v = stack.removeLast();
            return v == THIS ? -1 : v;
        }

        private int top(List<Integer> stack) {
            return stack.isEmpty() ? -1 : stack.getLast();
        }

        private static int descSlots(ClassDesc d) {
            if (d.isPrimitive()) {
                char c = d.descriptorString().charAt(0);
                return (c == 'J' || c == 'D') ? 2 : 1;
            }
            return 1;
        }

        private static int argsSlots(MethodTypeDesc d) {
            int n = 0;
            for (ClassDesc p : d.parameterList()) {
                n += descSlots(p);
            }
            return n;
        }
    }

    private static final class Result {
        final List<CodeElement> prefix;
        final List<CodeElement> movable;
        final CodeElement superElem;
        final List<CodeElement> late;
        final boolean failed;
        final boolean changed;

        Result(List<CodeElement> prefix, List<CodeElement> movable, CodeElement superElem,
                List<CodeElement> late, boolean failed, boolean changed) {
            this.prefix = prefix;
            this.movable = movable;
            this.superElem = superElem;
            this.late = late;
            this.failed = failed;
            this.changed = changed;
        }
    }
}
