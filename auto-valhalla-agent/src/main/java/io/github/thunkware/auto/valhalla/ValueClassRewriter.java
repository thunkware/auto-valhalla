package io.github.thunkware.auto.valhalla;

import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFileVersion;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rewrites a loaded class file into a JEP 401 value class.
 *
 * <p>An identity class is recorded with the {@code ACC_IDENTITY} flag
 * (historically {@code ACC_SUPER}) in its {@code access_flags}. A value class
 * is the same class without that flag, with all instance fields declared
 * {@code final strict}, and with constructors that assign every field before
 * {@code super()} is invoked. This class performs that transformation with the
 * {@code java.lang.classfile} API:
 *
 * <ul>
 *   <li>clears {@code ACC_IDENTITY} and sets {@code ACC_FINAL};</li>
 *   <li>sets {@code ACC_FINAL} and {@code ACC_STRICT} on instance fields;</li>
 *   <li>reorders constructor bodies (see {@link ConstructorRewriter});</li>
 *   <li>bumps the class-file version to the JDK 28 preview version so the JVM
 *       interprets the flags as value-class flags.</li>
 * </ul>
 *
 * <p>The result is verified with {@link ClassFile#verify(byte[])}. If the class
 * cannot be safely transformed (e.g. a field initializer depends on
 * {@code this}, or a strict field cannot be assigned before {@code super()}),
 * {@code null} is returned and the class is left as an identity class.
 */
public final class ValueClassRewriter {

    /** {@code ACC_SUPER} bit, repurposed by JEP 401 as {@code ACC_IDENTITY}. */
    static final int ACC_IDENTITY = 0x0020;
    /** JEP 539 strict-field flag. */
    static final int ACC_STRICT = 0x0800;
    static final int PREVIEW_MINOR_VERSION = 65535;
    static final int JAVA_28_MAJOR_VERSION = 72;

    static final String ANNOTATION_DESCRIPTOR = "Lio/github/thunkware/auto/valhalla/AutoValhalla;";

    private ValueClassRewriter() {}

    /**
     * @return the rewritten value-class bytes, or {@code null} if the class
     *         must remain an identity class
     */
    public static byte[] transform(ClassModel model, boolean keepIfInvalid,
            boolean ignoreSynchronized) {
        return transform(model, keepIfInvalid, ignoreSynchronized, false);
    }

    /**
     * Like {@link #transform(ClassModel, boolean, boolean)} but, when
     * {@code ignoreNonFinal} is true, a class that is not already final is still
     * accepted (it will be made final, which breaks any existing subclasses).
     */
    public static byte[] transform(ClassModel model, boolean keepIfInvalid,
            boolean ignoreSynchronized, boolean ignoreNonFinal) {
        if (!isSuitable(model, ignoreSynchronized, ignoreNonFinal)) {
            return null;
        }
        if (alreadyValue(model)) {
            return null;
        }

        AtomicBoolean ctorFailed = new AtomicBoolean(false);

        ClassTransform versionAndFlags = (cb, ce) -> {
            if (ce instanceof ClassFileVersion) {
                cb.withVersion(JAVA_28_MAJOR_VERSION, PREVIEW_MINOR_VERSION);
            } else if (ce instanceof AccessFlags af) {
                // Make it a value class: drop identity (ACC_SUPER) and make it
                // final. A value class must be final (JEP 401); abstract classes
                // are not accepted (see isSuitable), so there is no abstract case.
                int flags = af.flagsMask() & ~ACC_IDENTITY | ClassFile.ACC_FINAL;
                cb.withFlags(flags);
            } else {
                cb.accept(ce);
            }
        };
        ClassTransform strictFields = ClassTransform.transformingFields((fb, fe) -> {
            if (fe instanceof AccessFlags af && !af.has(AccessFlag.STATIC)) {
                fb.withFlags(af.flagsMask() | ClassFile.ACC_FINAL | ACC_STRICT);
            } else {
                fb.accept(fe);
            }
        });
        ClassTransform ctors = ConstructorRewriter.transformConstructors(model, ctorFailed);
        ClassTransform methods = ignoreSynchronized ? stripSynchronized() : ClassTransform.ACCEPT_ALL;

        byte[] out;
        try {
            out = ClassFile.of().transformClass(model,
                    versionAndFlags.andThen(strictFields).andThen(ctors).andThen(methods));
        } catch (RuntimeException e) {
            // e.g. strict-field-init violation detected while rebuilding code
            return null;
        }
        if (ctorFailed.get()) {
            return null;
        }
        List<VerifyError> errors = ClassFile.of().verify(out);
        if (!errors.isEmpty()) {
            return keepIfInvalid ? out : null;
        }
        return out;
    }

    /** Strips {@code ACC_SYNCHRONIZED} from every non-static method. */
    private static ClassTransform stripSynchronized() {
        return ClassTransform.transformingMethods((mb, me) -> {
            if (me instanceof AccessFlags af
                    && !af.has(AccessFlag.STATIC)
                    && af.has(AccessFlag.SYNCHRONIZED)) {
                mb.withFlags(af.flagsMask() & ~ClassFile.ACC_SYNCHRONIZED);
            } else {
                mb.accept(me);
            }
        });
    }

    /**
     * Structural prerequisites to become a value class.
     *
     * <p>{@link ClassFile#verify(byte[])} is a bytecode verifier: it checks that
     * the rewritten code is well-formed, but it does <em>not</em> enforce JEP 401
     * value-class legality. Rules that only the JVM class loader enforces must
     * therefore be checked here, otherwise the rewrite "succeeds" and the JVM
     * rejects the class file later with a {@link java.lang.ClassFormatError}
     * (e.g. {@code illegal modifiers: 0x21} for a synchronized instance method).
     *
     * <p>JEP 401 class-level rules enforced here:
     * <ul>
     *   <li>not an interface, enum, annotation, or module;</li>
     *   <li>the direct superclass is {@code java/lang/Object} or
     *       {@code java/lang/Record} — a value class may not extend an identity
     *       class;</li>
     *   <li>no non-static (instance) method carries {@code ACC_SYNCHRONIZED} — a
     *       value class cannot declare a synchronized instance method (unless
     *       {@code ignoreSynchronized} is set, in which case it is stripped);</li>
     *   <li>the class is final (made final below) or abstract (kept abstract
     *       below as an abstract value class).</li>
     * </ul>
     */
    public static boolean isSuitable(ClassModel model) {
        return isSuitable(model, false, false);
    }

    /**
     * Like {@link #isSuitable(ClassModel)} but, when {@code ignoreSynchronized} is
     * true, a class with synchronized instance methods is still considered
     * suitable (the caller is expected to strip them via
     * {@link #transform(ClassModel, boolean, boolean, boolean)}); and when
     * {@code ignoreNonFinal} is true, a class that is not already final is still
     * considered suitable (it will be made final, breaking any subclasses).
     */
    public static boolean isSuitable(ClassModel model, boolean ignoreSynchronized) {
        return isSuitable(model, ignoreSynchronized, false);
    }

    /**
     * The full structural check. See {@link #isSuitable(ClassModel, boolean)} for
     * the meaning of the flags.
     */
    public static boolean isSuitable(ClassModel model, boolean ignoreSynchronized,
            boolean ignoreNonFinal) {
        AccessFlags flags = model.flags();
        if (flags.has(AccessFlag.INTERFACE)
                || flags.has(AccessFlag.ENUM)
                || flags.has(AccessFlag.ANNOTATION)
                || flags.has(AccessFlag.MODULE)
                || flags.has(AccessFlag.ABSTRACT)) {
            return false;
        }
        // A value class may not extend an identity class.
        boolean superOk = model.superclass()
                .map(sc -> {
                    String n = sc.asInternalName();
                    return n.equals("java/lang/Object") || n.equals("java/lang/Record");
                })
                .orElse(false);
        if (!superOk) {
            return false;
        }
        // A value class must be final; making a non-final class final would break
        // its subclasses, so reject it unless the caller opts in (ignoreNonFinal).
        if (!ignoreNonFinal && !flags.has(AccessFlag.FINAL)) {
            return false;
        }
        // A value class cannot declare a synchronized instance method. The
        // bytecode verifier does not catch this, so the JVM would reject the
        // rewritten class at load with ClassFormatError: illegal modifiers 0x21.
        if (!ignoreSynchronized) {
            for (MethodModel m : model.methods()) {
                AccessFlags mf = m.flags();
                if (mf.has(AccessFlag.SYNCHRONIZED) && !mf.has(AccessFlag.STATIC)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True if the class file already describes a value class. */
    public static boolean alreadyValue(ClassModel model) {
        return model.minorVersion() == PREVIEW_MINOR_VERSION
                && (model.flags().flagsMask() & ACC_IDENTITY) == 0;
    }

    /**
     * True if every non-{@code final} instance field is written in <em>every</em>
     * constructor and never in a non-constructor method — i.e. the non-{@code
     * final} fields can safely be marked {@code final} (value-class) without the
     * risk of a later method re-assigning them. Already-{@code final} fields are
     * fine (nothing to mark). Used by the {@code mark-fields-final} mode to gate
     * selection.
     */
    public static boolean fieldsSafeToMarkFinal(ClassModel model) {
        String self = model.thisClass().asInternalName();
        List<Set<String>> ctorWrites = new ArrayList<>();
        AtomicBoolean bad = new AtomicBoolean(false);
        for (MethodModel m : model.methods()) {
            boolean ctor = m.methodName().stringValue().equals("<init>");
            Set<String> written = ctor ? new HashSet<>() : null;
            m.code().ifPresent(code -> code.elementList().forEach(e -> {
                if (bad.get()) {
                    return;
                }
                if (e instanceof FieldInstruction fi && fi.opcode() == Opcode.PUTFIELD
                        && fi.owner().asInternalName().equals(self)) {
                    if (!ctor) {
                        bad.set(true);
                        return;
                    }
                    written.add(fi.name().stringValue());
                }
            }));
            if (ctor) {
                ctorWrites.add(written);
            }
        }
        if (bad.get()) {
            return false;
        }
        for (FieldModel f : model.fields()) {
            if (f.flags().has(AccessFlag.STATIC) || f.flags().has(AccessFlag.FINAL)) {
                continue;
            }
            String name = f.fieldName().stringValue();
            // a non-final field must appear in every constructor
            for (Set<String> ctor : ctorWrites) {
                if (!ctor.contains(name)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True if the class is annotated with {@link AutoValhalla}. */
    public static boolean hasAutoValhallaAnnotation(ClassModel model) {
        return model.findAttribute(java.lang.classfile.Attributes.runtimeVisibleAnnotations())
                .map(attr -> attr.annotations().stream()
                        .anyMatch(a -> a.className().stringValue().equals(ANNOTATION_DESCRIPTOR)))
                .orElse(false);
    }
}
