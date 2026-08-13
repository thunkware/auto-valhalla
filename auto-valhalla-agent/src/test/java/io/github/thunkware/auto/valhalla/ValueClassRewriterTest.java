package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exercises the constructor-rewrite path for category-2 (long / double) fields,
 * which is the code path that uses {@code dup2} to capture the field value into
 * a local before the relocated {@code putfield}. A regression here produces
 * bytecode that fails verification or drops the value.
 */
class ValueClassRewriterTest {

    @Test
    void longAndDoubleFieldsBecomeValueClass() throws Exception {
        String internal = "sample/SampleX";
        byte[] original = readResource("/sample/SampleX.class");
        assertNotNull(original, "sample class must be on the test classpath");

        ClassFile cf = ClassFile.of();

        ValueClassTransformer transformer = new ValueClassTransformer(
                Set.of("sample.SampleX"), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, false, null, false, null);

        byte[] out = transformer.transform(null, null, internal, null, null, original);
        assertNotNull(out, "suitable class should be rewritten");
        var model = cf.parse(out);

        // A value class (per this agent's encoding) is a preview class whose
        // ACC_IDENTITY bit (0x0020, repurposed from ACC_SUPER) has been cleared.
        assertTrue(ValueClassRewriter.alreadyValue(model),
                "output should encode a value class");
        assertFalse((model.flags().flagsMask() & ValueClassRewriter.ACC_IDENTITY) != 0,
                "identity flag must be cleared");

        // The rewritten constructor bytecode must still verify.
        assertTrue(cf.verify(out).isEmpty(), "rewritten class must verify");
    }

    @Test
    void synchronizedInstanceMethodIsRejectedByYoloOnly() throws Exception {
        String internal = "sample/Sync";
        byte[] original = readResource("/sample/Sync.class");
        assertNotNull(original, "sample class must be on the test classpath");

        ClassFile cf = ClassFile.of();
        var model = cf.parse(original);
        assertFalse(ValueClassRewriter.isSuitable(model, false, false),
                "class with a synchronized instance method is not value-class suitable"
                        + " without ignore-synchronized");

        // mode=safe (no ignore modes) must not rewrite a selected class with a
        // synchronized instance method: it must remain a valid identity class,
        // never an unloadable value-class file.
        ValueClassTransformer transformer = new ValueClassTransformer(
                Set.of("sample.Sync"), Set.of(),
                EnumSet.noneOf(Mode.class), EnumSet.of(Mode.SAFE),
                false, false, null, false, null);
        byte[] out = transformer.transform(null, null, internal, null, null, original);
        assertNull(out, "synchronized-instance-method class must not be rewritten by includes-mode=safe");
    }

    @Test
    void ignoredSynchronizedStripsModifierAndProducesValueClass() throws Exception {
        String internal = "sample/Sync";
        byte[] original = readResource("/sample/Sync.class");
        assertNotNull(original, "sample class must be on the test classpath");

        ClassFile cf = ClassFile.of();
        var model = cf.parse(original);
        // mode=safe (or ignore-synchronized alone) only converts already-final
        // classes, so a non-final class with synchronized methods is rejected...
        assertFalse(ValueClassRewriter.isSuitable(model, false, false),
                "rejected without mark-class-final");
        // ...but the equivalent of annotation/includes (mark-class-final +
        // ignore-synchronized) makes it suitable and rewrites it successfully.
        assertTrue(ValueClassRewriter.isSuitable(model, true, true),
                "suitable with mark-class-final + ignore-synchronized");

        ValueClassTransformer transformer = new ValueClassTransformer(
                Set.of("sample.Sync"), Set.of(),
                EnumSet.noneOf(Mode.class),
                EnumSet.of(Mode.IGNORE_SYNCHRONIZED, Mode.MARK_CLASS_FINAL),
                false, false, null, false, null);
        byte[] out = transformer.transform(null, null, internal, null, null, original);
        assertNotNull(out, "synchronized non-final class should be rewritten with"
                + " mark-class-final + ignore-synchronized");

        var outModel = cf.parse(out);
        assertTrue(ValueClassRewriter.alreadyValue(outModel),
                "output should encode a value class");
        // The synchronized modifier must be gone from every non-static method,
        // while a static synchronized method is left untouched.
        var methods = outModel.methods().stream()
                .collect(java.util.stream.Collectors.toMap(
                        m -> m.methodName().stringValue(), m -> m));
        for (var m : outModel.methods()) {
            boolean isStatic = m.flags().has(AccessFlag.STATIC);
            assertEquals(isStatic, m.flags().has(AccessFlag.SYNCHRONIZED),
                    m.methodName().stringValue()
                            + ": static methods keep ACC_SYNCHRONIZED, instance methods lose it");
        }
        assertTrue(methods.get("staticMethod").flags().has(AccessFlag.SYNCHRONIZED),
                "static synchronized method must be preserved");
        assertFalse(methods.get("get").flags().has(AccessFlag.SYNCHRONIZED),
                "instance method get() must lose ACC_SYNCHRONIZED");
        assertFalse(methods.get("instance").flags().has(AccessFlag.SYNCHRONIZED),
                "instance method instance() must lose ACC_SYNCHRONIZED");
        // And the result must actually load as a value class (no ClassFormatError).
        assertTrue(cf.verify(out).isEmpty(), "rewritten class must verify");
    }

    @Test
    void safeModeNarrowsSelectionToFinalClasses() throws Exception {
        byte[] base = readResource("/sample/Base.class");
        assertNotNull(base, "Base on classpath");
        // Base is selected by includes; the default includes-mode would convert
        // it, but includes-mode=safe narrows selection to already-final classes.
        ValueClassTransformer safe = new ValueClassTransformer(
                Set.of("sample.Base"), Set.of(),
                EnumSet.noneOf(Mode.class), EnumSet.of(Mode.SAFE),
                false, false, null, false, null);
        assertNull(safe.transform(null, null, "sample.Base", null, null, base),
                "includes-mode=safe must not convert a non-final class (would break its subclasses)");
        ValueClassTransformer def = new ValueClassTransformer(
                Set.of("sample.Base"), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, false, null, false, null);
        assertNotNull(def.transform(null, null, "sample.Base", null, null, base),
                "default includes-mode must convert the selected non-final class");
    }

    @Test
    void markFieldsFinalModeRequiresPerConstructorWrites() throws Exception {
        byte[] once = readResource("/sample/Once.class");
        byte[] twoCtors = readResource("/sample/TwoCtors.class");
        byte[] sampleX = readResource("/sample/SampleX.class");
        byte[] mutable = readResource("/sample/Mutable.class");
        assertNotNull(once, "Once on classpath");
        assertNotNull(twoCtors, "TwoCtors on classpath");
        assertNotNull(sampleX, "SampleX on classpath");
        assertNotNull(mutable, "Mutable on classpath");

        ClassFile cf = ClassFile.of();
        // Once: non-final fields written in the (single) ctor -> can be marked final.
        assertTrue(ValueClassRewriter.fieldsSafeToMarkFinal(cf.parse(once)),
                "Once fields are non-final and written in the constructor");
        // TwoCtors: the field is written in every constructor, not just once globally.
        assertTrue(ValueClassRewriter.fieldsSafeToMarkFinal(cf.parse(twoCtors)),
                "TwoCtors.a is written in every constructor");
        // SampleX: fields already final -> nothing to mark, so it qualifies.
        assertTrue(ValueClassRewriter.fieldsSafeToMarkFinal(cf.parse(sampleX)),
                "SampleX fields are already final (no-op)");
        // Mutable: `v` is also written by the set() method -> cannot be marked final.
        assertFalse(ValueClassRewriter.fieldsSafeToMarkFinal(cf.parse(mutable)),
                "Mutable.v is written outside the constructor");

        EnumSet<Mode> mff = EnumSet.copyOf(Mode.INCLUDES_DEFAULT);
        mff.add(Mode.MARK_FIELDS_FINAL);
        ValueClassTransformer transformer = new ValueClassTransformer(
                Set.of("sample.Once", "sample.TwoCtors", "sample.Mutable", "sample.SampleX"),
                Set.of(), EnumSet.noneOf(Mode.class), mff, false, false, null, false, null);
        assertNotNull(transformer.transform(null, null, "sample.Once", null, null, once),
                "includes-mode=mark-fields-final converts classes with non-final fields written in the ctor");
        assertNotNull(transformer.transform(null, null, "sample.TwoCtors", null, null, twoCtors),
                "includes-mode=mark-fields-final allows a field written in every constructor");
        assertNotNull(transformer.transform(null, null, "sample.SampleX", null, null, sampleX),
                "includes-mode=mark-fields-final also converts classes with already-final fields");
        assertNull(transformer.transform(null, null, "sample.Mutable", null, null, mutable),
                "includes-mode=mark-fields-final rejects classes with a field written outside the ctor");
    }

    @Test
    void subclassOfTransformedFinalReportedBySuperclassName() throws Exception {
        byte[] base = readResource("/sample/Base.class");
        byte[] sub = readResource("/sample/Sub.class");
        assertNotNull(base, "Base on classpath");
        assertNotNull(sub, "Sub on classpath");
        ValueClassTransformer t = new ValueClassTransformer(
                Set.of("sample.Base"), Set.of(),
                EnumSet.noneOf(Mode.class), EnumSet.of(Mode.MARK_CLASS_FINAL),
                false, false, null, false, null);
        assertNotNull(t.transform(null, null, "sample.Base", null, null, base),
                "Base rewrites (made final)");
        // Sub extends Base, which was just made final; loading Sub must now fail,
        // and the agent reports the offending superclass by name.
        LinkageError ex = assertThrows(LinkageError.class,
                () -> t.transform(null, null, "sample.Sub", null, null, sub));
        assertTrue(ex.getMessage().contains("sample/Base"),
                "subclass failure must name its superclass: " + ex.getMessage());
    }

    @Test
    void subclassOfRewrittenAbstractValueClassIsStillSuitable() throws Exception {
        byte[] absBase = readResource("/sample/AbstractBase.class");
        byte[] absSub = readResource("/sample/AbstractSub.class");
        assertNotNull(absBase, "AbstractBase on classpath");
        assertNotNull(absSub, "AbstractSub on classpath");
        ClassFile cf = ClassFile.of();

        // Rewrite the abstract superclass into an abstract value class first.
        ValueClassTransformer t = new ValueClassTransformer(
                Set.of("sample.AbstractBase", "sample.AbstractSub"), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, false, null, false, null);
        byte[] baseOut = t.transform(null, null, "sample/AbstractBase", null, null, absBase);
        assertNotNull(baseOut, "abstract class is rewritten into an abstract value class");
        var baseModel = cf.parse(baseOut);
        assertTrue(baseModel.flags().has(AccessFlag.ABSTRACT),
                "the rewritten base must keep ACC_ABSTRACT");
        assertTrue(ValueClassRewriter.alreadyValue(baseModel),
                "the rewritten base must be a value class");

        // Its subclass must now be a suitable candidate: a value class may extend
        // an abstract value class. (No LinkageError, unlike a final superclass.)
        assertTrue(ValueClassRewriter.suitabilityProblems(
                        cf.parse(absSub), false, true, Set.of("sample/AbstractBase")).isEmpty(),
                "AbstractSub extends a rewritten abstract value class, so it is suitable");
        assertTrue(ValueClassRewriter.suitabilityProblems(
                        cf.parse(absSub), false, false, Set.of("sample/AbstractBase")).stream()
                        .anyMatch(p -> p.contains("it is not final")),
                "without mark-class-final the non-final AbstractSub still lacks finality");
        assertTrue(ValueClassRewriter.suitabilityProblems(
                        cf.parse(absSub), false, true).stream()
                        .anyMatch(p -> p.contains("extends the identity class sample/AbstractBase")),
                "without the rewrite history the abstract superclass is treated as an identity class");

        byte[] subOut = t.transform(null, null, "sample/AbstractSub", null, null, absSub);
        assertNotNull(subOut, "AbstractSub rewrites once its superclass is an abstract value class");
        assertTrue(cf.verify(subOut).isEmpty(), "rewritten AbstractSub must verify");
    }

    @Test
    void parseModeIsCaseAndSeparatorInsensitive() {
        assertEquals(EnumSet.of(Mode.SAFE),
                Mode.parse("SAFE"));
        assertEquals(EnumSet.of(Mode.MARK_CLASS_FINAL),
                Mode.parse("mark-class-final"));
        assertEquals(EnumSet.of(Mode.MARK_CLASS_FINAL),
                Mode.parse("markClassFinal"));
        assertEquals(EnumSet.of(Mode.IGNORE_SYNCHRONIZED),
                Mode.parse("IGNORE-SYNCHRONIZED"));
        assertEquals(EnumSet.of(Mode.SAFE,
                        Mode.IGNORE_SYNCHRONIZED),
                Mode.parse("safe,ignore-synchronized"));
        assertEquals(EnumSet.of(Mode.SAFE,
                        Mode.MARK_CLASS_FINAL,
                        Mode.IGNORE_SYNCHRONIZED),
                Mode.parse("Safe,Mark-Class-Final,IGNORE_SYNCHRONIZED"));
        // the default includes-mode set and the yolo expansion
        assertEquals(Mode.INCLUDES_DEFAULT, Mode.parse(null));
        assertEquals(Mode.INCLUDES_DEFAULT, Mode.parse("  "));
        assertEquals(Mode.INCLUDES_DEFAULT, Mode.parse("unknown-token"));
        // yolo is a shorthand for the default includes-mode
        assertEquals(Mode.INCLUDES_DEFAULT, Mode.parse("yolo"));
        EnumSet<Mode> safeYolo = EnumSet.of(Mode.SAFE);
        safeYolo.addAll(Mode.INCLUDES_DEFAULT);
        assertEquals(safeYolo, Mode.parse("safe,yolo"));
        // the default annotation-mode set is safe (no mark-class-final)
        assertEquals(EnumSet.of(Mode.SAFE), Mode.ANNOTATION_DEFAULT);
    }

    @Test
    void suitabilityProblemsAreTargetedToTheActualViolation() throws Exception {
        byte[] sub = readResource("/sample/Sub.class");
        byte[] sync = readResource("/sample/Sync.class");
        assertNotNull(sub, "Sub on classpath");
        assertNotNull(sync, "Sync on classpath");

        ClassFile cf = ClassFile.of();

        // Sub: an identity superclass AND not final -> both problems are listed,
        // each targeted at its own condition.
        List<String> subProblems =
                ValueClassRewriter.suitabilityProblems(cf.parse(sub), false, false);
        assertEquals(2, subProblems.size(), "Sub violates exactly two rules: " + subProblems);
        assertTrue(subProblems.stream().anyMatch(p -> p.contains("extends the identity class sample/Base")),
                "the superclass problem must name the identity class: " + subProblems);
        assertTrue(subProblems.stream().anyMatch(p -> p.contains("it is not final")),
                "the final problem must be reported: " + subProblems);

        // Sync (with mark-class-final, since Sync itself is not final): the
        // ONLY remaining violation is synchronized instance methods, so the
        // message must mention only that (naming the methods), not finals or
        // superclasses.
        List<String> syncProblems =
                ValueClassRewriter.suitabilityProblems(cf.parse(sync), false, true);
        assertEquals(1, syncProblems.size(), "Sync violates only the synchronized rule");
        String syncMsg = syncProblems.get(0);
        assertTrue(syncMsg.contains("synchronized instance method(s) get, instance"),
                "the synchronized problem must name the methods: " + syncMsg);
        assertTrue(syncMsg.contains("use ignore-synchronized to strip it"), syncMsg);
        assertFalse(syncMsg.contains("final"), "sync-only message must not mention final: " + syncMsg);
        assertFalse(syncMsg.contains("extends") || syncMsg.contains("superclass"),
                "sync-only message must not mention the superclass: " + syncMsg);

        // Once the flags address the violations, no problems remain.
        assertTrue(ValueClassRewriter.suitabilityProblems(cf.parse(sub), false, true).stream()
                .noneMatch(p -> p.contains("not final")),
                "mark-class-final clears the final problem");
        assertTrue(ValueClassRewriter.suitabilityProblems(cf.parse(sync), true, true).isEmpty(),
                "ignore-synchronized + mark-class-final clears all problems");
    }

    @Test
    void starPatternMatchesEverything() {
        assertTrue(ValueClassTransformer.patternMatches(Set.of("*"), "any/pkg/Cls"));
        assertFalse(ValueClassTransformer.patternMatches(Set.of(), "any/pkg/Cls"));
    }

    @Test
    void onFailThrowIsPerSelectionSource() throws Exception {        byte[] mutable = readResource("/sample/Mutable.class");
        assertNotNull(mutable, "Mutable on classpath");
        String internal = "sample/Mutable";

        // A mutable field written by a setter fails the mark-fields-final gate.
        // includes.on-fail-throw=false leaves it an identity class...
        ValueClassTransformer includesQuiet = new ValueClassTransformer(
                Set.of("sample.Mutable"), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, false, null, false, null);
        assertNull(includesQuiet.transform(null, null, internal, null, null, mutable),
                "includes.on-fail-throw=false leaves the class as identity");

        // ...while includes.on-fail-throw=true surfaces the rejection loudly
        // (an unloadable class file, never a usable value class).
        ValueClassTransformer includesLoud = new ValueClassTransformer(
                Set.of("sample.Mutable"), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, false, null, true, null);
        byte[] out = includesLoud.transform(null, null, internal, null, null, mutable);
        assertNotNull(out, "includes.on-fail-throw=true surfaces the rejection");
        assertFalse(DemoFixturesTest.isUsableValueClass(out),
                "a loud rejection is not a usable value class");

        // Annotation-selected classes default to loud: under the safe default
        // annotation-mode, a non-final annotated class is skipped silently
        // before the on-fail setting can apply...
        byte[] mp = readResource("/demo5/broken/MutablePoint.class");
        assertNotNull(mp, "MutablePoint on classpath");
        ValueClassTransformer annoSafe = new ValueClassTransformer(
                Set.of(), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, true, null, false, null);
        assertNull(annoSafe.transform(null, null, "demo5/broken/MutablePoint", null, null, mp),
                "the safe default skips a non-final annotated class silently");

        // ...so the loud annotation.on-fail setting only fires for classes that
        // are expression candidates: opting into mark-class-final makes the
        // mutable annotated class fail loudly, and the result is never a usable
        // value class.
        ValueClassTransformer annoLoud = new ValueClassTransformer(
                Set.of(), Set.of(),
                EnumSet.of(Mode.MARK_CLASS_FINAL, Mode.IGNORE_SYNCHRONIZED),
                Mode.INCLUDES_DEFAULT,
                false, true, null, false, null);
        byte[] mpOut = annoLoud.transform(null, null, "demo5/broken/MutablePoint", null, null, mp);
        assertNotNull(mpOut, "annotation.on-fail-throw defaults to true for annotated classes");
        assertFalse(DemoFixturesTest.isUsableValueClass(mpOut),
                "the annotation-default rejection is not a usable value class");
    }

    @Test
    void unselectedUnparseableClassIsNotLoud() throws Exception {
        // A class that is neither annotated nor included, whose classfile cannot
        // be parsed, must not take the loud annotation.on-fail-throw default (it
        // would crash the whole app for a class the agent never selected).
        byte[] garbage = new byte[] { 0, 0, 0, 0 };
        ValueClassTransformer t = new ValueClassTransformer(
                Set.of(), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, true, null, true, null);
        assertNull(t.transform(null, null, "com/example/Unselected", null, null, garbage),
                "an unparseable unselected class stays an identity class");
    }

    @Test
    void onFailAppendIsPerSelectionSource() throws Exception {
        File ann = File.createTempFile("ann", ".log");
        File inc = File.createTempFile("inc", ".log");
        try {
            // Selected by BOTH annotation and includes: the annotation settings
            // win, so the failure is recorded in the annotation file only.
            byte[] mp = readResource("/demo5/broken/MutablePoint.class");
            ValueClassTransformer both = new ValueClassTransformer(
                    Set.of("demo5"), Set.of(),
                    Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                    false, true, ann.getAbsolutePath(), true, inc.getAbsolutePath());
            both.transform(null, null, "demo5/broken/MutablePoint", null, null, mp);
            assertEquals("demo5.broken.MutablePoint\n", Files.readString(ann.toPath()),
                    "a both-selected class is appended to the annotation file as a dot name");
            assertTrue(Files.readString(inc.toPath()).isEmpty(),
                    "the includes file is untouched when the annotation settings win");

            // Includes-only selection appends to the includes file.
            byte[] mutable = readResource("/sample/Mutable.class");
            File inc2 = File.createTempFile("inc2", ".log");
            try {
                ValueClassTransformer includesOnly = new ValueClassTransformer(
                        Set.of("sample.Mutable"), Set.of(),
                        Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                        false, false, null, false, inc2.getAbsolutePath());
                includesOnly.transform(null, null, "sample/Mutable", null, null, mutable);
                assertEquals("sample.Mutable\n", Files.readString(inc2.toPath()),
                        "an includes-only class is appended to the includes file as a dot name");
            } finally {
                inc2.delete();
            }
        } finally {
            ann.delete();
            inc.delete();
        }
    }

    private byte[] readResource(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
