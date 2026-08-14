package io.github.thunkware.auto.valhalla;

import static java.lang.classfile.Attributes.runtimeVisibleAnnotations;

import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFileVersion;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
 *   <li>clears {@code ACC_IDENTITY} and sets {@code ACC_FINAL} (concrete
 *       classes; abstract classes are never converted and stay identity
 *       classes — see {@link #suitabilityProblems(ClassModel, boolean,
 *       boolean)});</li>
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

    static final String ANNOTATION_DESCRIPTOR = "Lio/github/thunkware/auto/valhalla/api/AutoValhalla;";

    private ValueClassRewriter() {}

    /**
     * @return the rewritten value-class bytes, or {@code null} if the class
     *         must remain an identity class
     */
    public static byte[] transform(ClassModel model, boolean keepIfInvalid,
            boolean removeSynchronized, ClassLoader loader) {
        return transform(model, keepIfInvalid, removeSynchronized, false, loader);
    }

    /**
     * Like {@link #transform(ClassModel, boolean, boolean)} but, when
     * {@code markClassFinal} is true, a class that is not already final is still
     * accepted (it will be marked final, which breaks any existing subclasses).
     * Abstract classes are never converted (see
     * {@link #suitabilityProblems(ClassModel, boolean, boolean)}): an
     * agent-converted abstract value class whose identity subclass is loaded
     * later triggers a duplicate class definition in the JVM, so abstract
     * classes are left as identity classes.
     */
    public static byte[] transform(ClassModel model, boolean keepIfInvalid,
            boolean removeSynchronized, boolean markClassFinal, ClassLoader loader) {
        if (!isSuitable(model, removeSynchronized, markClassFinal)) {
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
                // Make it a value class: drop identity (ACC_SUPER). A concrete
                // value class must be final (JEP 401). Abstract classes never
                // reach this point (they are not suitable, see suitabilityProblems),
                // so every rewritten class is marked final.
                cb.withFlags(af.flagsMask() & ~ACC_IDENTITY | ClassFile.ACC_FINAL);
            } else if (ce instanceof InnerClassesAttribute ica) {
                // The JVM only injects ACC_IDENTITY into InnerClasses-attribute
                // entries for legacy class files; for a value-class (inline)
                // version each entry must carry one of ACC_IDENTITY /
                // ACC_INTERFACE / (ACC_ABSTRACT | ACC_FINAL) itself, or
                // ClassFileParser throws "Illegal class modifiers in inner
                // class ... of class ...". Member classes declared in an
                // identity class file are identity classes, so mark every
                // non-interface, non-module entry with ACC_IDENTITY, matching
                // exactly what HotSpot does for pre-inline class files.
                List<InnerClassInfo> entries = ica.classes().stream()
                        .map(info -> {
                            int mask = info.flagsMask();
                            if ((mask & (ACC_IDENTITY | ClassFile.ACC_INTERFACE
                                    | ClassFile.ACC_MODULE)) == 0) {
                                info = InnerClassInfo.of(info.innerClass(),
                                        info.outerClass(), info.innerName(),
                                        mask | ACC_IDENTITY);
                            }
                            return info;
                        })
                        .toList();
                cb.with(InnerClassesAttribute.of(entries));
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
        ClassTransform methods = removeSynchronized ? stripSynchronized() : ClassTransform.ACCEPT_ALL;

        ClassFile cf = ClassFiles.of(loader);
        byte[] out;
        try {
            out = cf.transformClass(model,
                    versionAndFlags.andThen(strictFields).andThen(ctors).andThen(methods));
        } catch (RuntimeException e) {
            // e.g. strict-field-init violation detected while rebuilding code
            return null;
        }
        if (ctorFailed.get()) {
            return null;
        }
        List<VerifyError> errors = cf.verify(out);
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
     *   <li>not abstract — an abstract class is never converted, because a later
     *       identity subclass of an agent-converted abstract value class triggers
     *       a duplicate class definition in the JVM (only an identity hierarchy
     *       or a wholly-rewritten value hierarchy is safe);</li>
     *   <li>the direct superclass is {@code java/lang/Object} or {@code
     *       java/lang/Record} — a value class may extend only {@code
     *       java.lang.Object} or {@code java.lang.Record}, not an identity
     *       class;</li>
     *   <li>no non-static (instance) method carries {@code ACC_SYNCHRONIZED} — a
     *       value class cannot declare a synchronized instance method (unless
     *       {@code removeSynchronized} is set, in which case it is stripped);</li>
     *   <li>the class is final (made final below).</li>
     * </ul>
     */
    public static boolean isSuitable(ClassModel model) {
        return isSuitable(model, false, false);
    }

    /**
     * Like {@link #isSuitable(ClassModel)} but, when {@code removeSynchronized} is
     * true, a class with synchronized instance methods is still considered
     * suitable (the caller is expected to strip them via
     * {@link #transform(ClassModel, boolean, boolean, boolean)}); and when
     * {@code markClassFinal} is true, a class that is neither final nor abstract
     * is still considered suitable (it will be marked final, breaking any
     * subclasses).
     */
    public static boolean isSuitable(ClassModel model, boolean removeSynchronized) {
        return isSuitable(model, removeSynchronized, false);
    }

    /**
     * The full structural check. See {@link #isSuitable(ClassModel, boolean)} for
     * the meaning of the flags.
     */
    public static boolean isSuitable(ClassModel model, boolean removeSynchronized,
            boolean markClassFinal) {
        return suitabilityProblems(model, removeSynchronized, markClassFinal).isEmpty();
    }

    /**
     * Reports, as targeted messages, every JEP 401 structural rule the class
     * violates, so callers can surface <em>only</em> the actual problem(s)
     * instead of a blanket "not suitable". Empty when the class is a suitable
     * value-class candidate (see {@link #isSuitable(ClassModel, boolean, boolean)}
     * for what the {@code ignore*} flags mean).
     */
    public static List<String> suitabilityProblems(ClassModel model,
            boolean removeSynchronized, boolean markClassFinal) {
        List<String> problems = new ArrayList<>();
        AccessFlags flags = model.flags();
        if (flags.has(AccessFlag.INTERFACE)) {
            problems.add("it is an interface; a value class cannot be an interface");
        }
        if (flags.has(AccessFlag.ENUM)) {
            problems.add("it is an enum; a value class cannot be an enum");
        }
        if (flags.has(AccessFlag.ANNOTATION)) {
            problems.add("it is an annotation type; a value class cannot be an annotation type");
        }
        if (flags.has(AccessFlag.MODULE)) {
            problems.add("it is a module; a value class cannot be a module");
        }
        if (flags.has(AccessFlag.ABSTRACT)) {
            problems.add("it is abstract; converting an abstract class is not yet supported");
        }
        // A class with no instance fields has no state to flatten: converting it
        // would change identity semantics for no benefit (and is usually a
        // utility class with only static members, which should not become a value
        // class).
        boolean hasInstanceField = model.fields().stream()
                .anyMatch(f -> !f.flags().has(AccessFlag.STATIC));
        if (!hasInstanceField) {
            problems.add("it has no instance fields; a value class should have instance state");
        }
        String sup = model.superclass().map(ClassEntry::asInternalName)
                .orElse("java/lang/Object");
        if (!sup.equals("java/lang/Object") && !sup.equals("java/lang/Record")) {
            problems.add("it extends the identity class " + sup
                    + "; a value class can extend only java.lang.Object or"
                    + " java.lang.Record, not an identity class");
        }
        if (!markClassFinal && !flags.has(AccessFlag.FINAL)
                && !flags.has(AccessFlag.ABSTRACT)) {
            problems.add("it is not final; a value class must be final (use"
                    + " mark-class-final to convert it by marking it final, which"
                    + " breaks any existing subclasses)");
        }
        if (!removeSynchronized) {
            String sync = model.methods().stream()
                    .filter(m -> m.flags().has(AccessFlag.SYNCHRONIZED)
                            && !m.flags().has(AccessFlag.STATIC))
                    .map(m -> m.methodName().stringValue())
                    .collect(Collectors.joining(", "));
            if (!sync.isEmpty()) {
                problems.add("it declares synchronized instance method(s) " + sync
                        + "; a value class cannot have a synchronized instance"
                        + " method (use remove-synchronized to strip it)");
            }
        }
        // A synchronized block (monitorenter) is never safe in a value class: a
        // value object has no identity, so synchronizing on it throws
        // IdentityException at runtime. Unlike ACC_SYNCHRONIZED, monitorenter
        // cannot be stripped, so this is rejected regardless of
        // remove-synchronized.
        String syncBlocks = model.methods().stream()
                .filter(m -> m.code().map(code -> code.elementList().stream()
                        .anyMatch(e -> e instanceof Instruction i
                                && i.opcode() == Opcode.MONITORENTER))
                        .orElse(false))
                .map(m -> m.methodName().stringValue())
                .collect(Collectors.joining(", "));
        if (!syncBlocks.isEmpty()) {
            problems.add("it uses a synchronized block (monitorenter) in method(s) " + syncBlocks
                    + "; a value object has no identity, so synchronizing on it"
                    + " throws IdentityException at runtime");
        }
        // The rewrite marks every non-static field final and strict. That is only
        // safe if no other class can write the field: a non-private (public /
        // protected / package-private) field may be mutated by sibling classes,
        // which would then fail with IllegalAccessError (e.g. H2's
        // org.h2.mvstore.CursorPos.index written by org.h2.mvstore.Cursor).
        String openFields = model.fields().stream()
                .filter(f -> !f.flags().has(AccessFlag.STATIC)
                        && !f.flags().has(AccessFlag.FINAL)
                        && !f.flags().has(AccessFlag.PRIVATE))
                .map(f -> f.fieldName().stringValue())
                .collect(Collectors.joining(", "));
        if (!openFields.isEmpty()) {
            problems.add("it has non-private mutable field(s) " + openFields
                    + "; another class may write them, so they cannot be marked final");
        }
        return problems;
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
        return model.findAttribute(runtimeVisibleAnnotations())
                .map(attr -> attr.annotations().stream()
                        .anyMatch(a -> a.className().stringValue().equals(ANNOTATION_DESCRIPTOR)))
                .orElse(false);
    }
}
