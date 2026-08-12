package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.reflect.AccessFlag;
import java.util.EnumSet;
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
                Mode.getDefaultModes(), false, false, null);

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
                EnumSet.of(Mode.SAFE), false, false, null);
        byte[] out = transformer.transform(null, null, internal, null, null, original);
        assertNull(out, "synchronized-instance-method class must not be rewritten by mode=safe");
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
                "rejected without ignore-non-final");
        // ...but the equivalent of annotation/includes (ignore-non-final +
        // ignore-synchronized) makes it suitable and rewrites it successfully.
        assertTrue(ValueClassRewriter.isSuitable(model, true, true),
                "suitable with ignore-non-final + ignore-synchronized");

        ValueClassTransformer transformer = new ValueClassTransformer(
                Set.of("sample.Sync"), Set.of(),
                EnumSet.of(Mode.IGNORE_SYNCHRONIZED,
                        Mode.IGNORE_NON_FINAL),
                false, false, null);
        byte[] out = transformer.transform(null, null, internal, null, null, original);
        assertNotNull(out, "synchronized non-final class should be rewritten with"
                + " ignore-non-final + ignore-synchronized");

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
        // Base is selected by includes; default mode would convert it, but
        // mode=safe narrows selection to already-final classes only.
        ValueClassTransformer safe = new ValueClassTransformer(
                Set.of("sample.Base"), Set.of(),
                EnumSet.of(Mode.SAFE),
                false, false, null);
        assertNull(safe.transform(null, null, "sample.Base", null, null, base),
                "mode=safe must not convert a non-final class (would break its subclasses)");
        ValueClassTransformer def = new ValueClassTransformer(
                Set.of("sample.Base"), Set.of(),
                Mode.getDefaultModes(),
                false, false, null);
        assertNotNull(def.transform(null, null, "sample.Base", null, null, base),
                "default mode must convert the selected non-final class");
    }

    @Test
    void markFieldsFinalModeRequiresSingleConstructorWrite() throws Exception {
        byte[] once = readResource("/sample/Once.class");
        byte[] sampleX = readResource("/sample/SampleX.class");
        byte[] mutable = readResource("/sample/Mutable.class");
        assertNotNull(once, "Once on classpath");
        assertNotNull(sampleX, "SampleX on classpath");
        assertNotNull(mutable, "Mutable on classpath");

        ClassFile cf = ClassFile.of();
        // Once: non-final fields written once in the ctor -> can be marked final.
        assertTrue(ValueClassRewriter.fieldsSafeToMarkFinal(cf.parse(once)),
                "Once fields are non-final and written once in the constructor");
        // SampleX: fields already final -> nothing to mark, so it qualifies.
        assertTrue(ValueClassRewriter.fieldsSafeToMarkFinal(cf.parse(sampleX)),
                "SampleX fields are already final (no-op)");
        // Mutable: `v` is also written by the set() method -> cannot be marked final.
        assertFalse(ValueClassRewriter.fieldsSafeToMarkFinal(cf.parse(mutable)),
                "Mutable.v is written outside the constructor");

        EnumSet<Mode> mff = EnumSet.copyOf(Mode.getDefaultModes());
        mff.add(Mode.MARK_FIELDS_FINAL);
        ValueClassTransformer transformer = new ValueClassTransformer(
                Set.of("sample.Once", "sample.Mutable", "sample.SampleX"), Set.of(),
                mff,
                false, false, null);
        assertNotNull(transformer.transform(null, null, "sample.Once", null, null, once),
                "mode=mark-fields-final converts classes with non-final fields written once in the ctor");
        assertNotNull(transformer.transform(null, null, "sample.SampleX", null, null, sampleX),
                "mode=mark-fields-final also converts classes with already-final fields");
        assertNull(transformer.transform(null, null, "sample.Mutable", null, null, mutable),
                "mode=mark-fields-final rejects classes with a field written outside the ctor");
    }

    @Test
    void subclassOfTransformedFinalReportedBySuperclassName() throws Exception {
        byte[] base = readResource("/sample/Base.class");
        byte[] sub = readResource("/sample/Sub.class");
        assertNotNull(base, "Base on classpath");
        assertNotNull(sub, "Sub on classpath");
        ValueClassTransformer t = new ValueClassTransformer(
                Set.of("sample.Base"), Set.of(),
                EnumSet.of(Mode.IGNORE_NON_FINAL),
                false, false, null);
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
    void parseModeIsCaseAndSeparatorInsensitive() {
        assertEquals(EnumSet.of(Mode.SAFE),
                Mode.parse("SAFE"));
        assertEquals(EnumSet.of(Mode.IGNORE_NON_FINAL),
                Mode.parse("ignore-non-final"));
        assertEquals(EnumSet.of(Mode.IGNORE_NON_FINAL),
                Mode.parse("ignoreNonFinal"));
        assertEquals(EnumSet.of(Mode.IGNORE_SYNCHRONIZED),
                Mode.parse("IGNORE-SYNCHRONIZED"));
        assertEquals(EnumSet.of(Mode.SAFE,
                        Mode.IGNORE_SYNCHRONIZED),
                Mode.parse("safe,ignore-synchronized"));
        assertEquals(EnumSet.of(Mode.SAFE,
                        Mode.IGNORE_NON_FINAL,
                        Mode.IGNORE_SYNCHRONIZED),
                Mode.parse("Safe,Ignore-Non-Final,IGNORE_SYNCHRONIZED"));
        // the default mode set is ignore-non-final + ignore-synchronized
        assertEquals(Mode.getDefaultModes(), Mode.parse(null));
        assertEquals(Mode.getDefaultModes(), Mode.parse("  "));
        assertEquals(Mode.getDefaultModes(), Mode.parse("unknown-token"));
        // yolo is a shorthand for the default modes
        assertEquals(Mode.getDefaultModes(), Mode.parse("yolo"));
        EnumSet<Mode> safeYolo = EnumSet.of(Mode.SAFE);
        safeYolo.addAll(Mode.getDefaultModes());
        assertEquals(safeYolo, Mode.parse("safe,yolo"));
    }

    @Test
    void starPatternMatchesEverything() {
        assertTrue(ValueClassTransformer.patternMatches(Set.of("*"), "any/pkg/Cls"));
        assertFalse(ValueClassTransformer.patternMatches(Set.of(), "any/pkg/Cls"));
    }

    private byte[] readResource(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
