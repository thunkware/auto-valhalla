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
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
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

        Config cfg = new Config();
        cfg.includes = Set.of("sample.SampleX");
        cfg.excludes = Set.of();
        cfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        cfg.includesMode = Mode.INCLUDES_DEFAULT;
        ValueClassTransformer transformer = new ValueClassTransformer(cfg);

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
        Config cfg = new Config();
        cfg.includes = Set.of("sample.Sync");
        cfg.excludes = Set.of();
        cfg.annotationMode = EnumSet.noneOf(Mode.class);
        cfg.includesMode = EnumSet.of(Mode.SAFE);
        ValueClassTransformer transformer = new ValueClassTransformer(cfg);
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

        Config cfg = new Config();
        cfg.includes = Set.of("sample.Sync");
        cfg.excludes = Set.of();
        cfg.annotationMode = EnumSet.noneOf(Mode.class);
        cfg.includesMode = EnumSet.of(Mode.IGNORE_SYNCHRONIZED, Mode.MARK_CLASS_FINAL);
        ValueClassTransformer transformer = new ValueClassTransformer(cfg);
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
    void memberClassAccessFlagsGainIdentityWhenBumpingVersion() throws Exception {
        String internal = "sample/NestedOuter";
        byte[] original = readResource("/sample/NestedOuter.class");
        assertNotNull(original, "sample class must be on the test classpath");

        ClassFile cf = ClassFile.of();
        var inModel = cf.parse(original);
        // The compiled class (pre-inline version) records its member classes
        // with ACC_STATIC only -- no ACC_IDENTITY, matching what Spring Boot's
        // FileDataBlock does. The rewriter must add ACC_IDENTITY itself once it
        // bumps the version, or the JVM rejects the class at load time with
        // "Illegal class modifiers in inner class ... of class ...".
        var inInner = inModel.findAttribute(java.lang.classfile.Attributes.innerClasses()).orElseThrow();
        assertFalse(inInner.classes().stream()
                        .allMatch(ici -> (ici.flagsMask() & ValueClassRewriter.ACC_IDENTITY) != 0),
                "the input's InnerClasses entries must lack ACC_IDENTITY to prove the fix");

        Config cfg = new Config();
        cfg.includes = Set.of(internal);
        cfg.excludes = Set.of();
        cfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        cfg.includesMode = Mode.INCLUDES_DEFAULT;
        ValueClassTransformer transformer = new ValueClassTransformer(cfg);
        byte[] out = transformer.transform(null, null, internal, null, null, original);
        assertNotNull(out, "a class with member classes should be rewritten");

        var outModel = cf.parse(out);
        assertTrue(ValueClassRewriter.alreadyValue(outModel),
                "output should encode a value class");
        var outInner = outModel.findAttribute(java.lang.classfile.Attributes.innerClasses()).orElseThrow();
        assertFalse(outInner.classes().isEmpty(), "output keeps its InnerClasses attribute");
        for (var ici : outInner.classes()) {
            int mask = ici.flagsMask();
            assertTrue((mask & ValueClassRewriter.ACC_IDENTITY) != 0
                            || (mask & ClassFile.ACC_INTERFACE) != 0
                            || (mask & ClassFile.ACC_MODULE) != 0,
                    "each member-class entry must carry ACC_IDENTITY (or be an interface)"
                            + " so the JVM accepts the value-class version: " + ici.innerClass().asInternalName());
        }
        // The rewritten class must load as a value class: no ClassFormatError.
        assertTrue(cf.verify(out).isEmpty(), "rewritten class must verify");
    }

    @Test
    void safeModeNarrowsSelectionToFinalClasses() throws Exception {
        byte[] base = readResource("/sample/Base.class");
        assertNotNull(base, "Base on classpath");
        // Base is selected by includes; the default includes-mode would convert
        // it, but includes-mode=safe narrows selection to already-final classes.
        Config safeCfg = new Config();
        safeCfg.includes = Set.of("sample.Base");
        safeCfg.excludes = Set.of();
        safeCfg.annotationMode = EnumSet.noneOf(Mode.class);
        safeCfg.includesMode = EnumSet.of(Mode.SAFE);
        ValueClassTransformer safe = new ValueClassTransformer(safeCfg);
        assertNull(safe.transform(null, null, "sample/Base", null, null, base),
                "includes-mode=safe must not convert a non-final class (would break its subclasses)");

        Config defCfg = new Config();
        defCfg.includes = Set.of("sample.Base");
        defCfg.excludes = Set.of();
        defCfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        defCfg.includesMode = Mode.INCLUDES_DEFAULT;
        ValueClassTransformer def = new ValueClassTransformer(defCfg);
        assertNotNull(def.transform(null, null, "sample/Base", null, null, base),
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
        Config cfg = new Config();
        cfg.includes = Set.of("sample.Once", "sample.TwoCtors", "sample.Mutable", "sample.SampleX");
        cfg.excludes = Set.of();
        cfg.annotationMode = EnumSet.noneOf(Mode.class);
        cfg.includesMode = mff;
        ValueClassTransformer transformer = new ValueClassTransformer(cfg);
        assertNotNull(transformer.transform(null, null, "sample/Once", null, null, once),
                "includes-mode=mark-fields-final converts classes with non-final fields written in the ctor");
        assertNotNull(transformer.transform(null, null, "sample/TwoCtors", null, null, twoCtors),
                "includes-mode=mark-fields-final allows a field written in every constructor");
        assertNotNull(transformer.transform(null, null, "sample/SampleX", null, null, sampleX),
                "includes-mode=mark-fields-final also converts classes with already-final fields");
        assertNull(transformer.transform(null, null, "sample/Mutable", null, null, mutable),
                "includes-mode=mark-fields-final rejects classes with a field written outside the ctor");
    }

    @Test
    void subclassOfTransformedFinalReportedBySuperclassName() throws Exception {
        byte[] base = readResource("/sample/Base.class");
        byte[] sub = readResource("/sample/Sub.class");
        assertNotNull(base, "Base on classpath");
        assertNotNull(sub, "Sub on classpath");
        Config cfg = new Config();
        cfg.includes = Set.of("sample.Base");
        cfg.excludes = Set.of();
        cfg.annotationMode = EnumSet.noneOf(Mode.class);
        cfg.includesMode = EnumSet.of(Mode.MARK_CLASS_FINAL);
        ValueClassTransformer t = new ValueClassTransformer(cfg);
        assertNotNull(t.transform(null, null, "sample/Base", null, null, base),
                "Base rewrites (made final)");
        // Sub extends Base, which was just made final; loading Sub must now fail,
        // and the agent reports the offending superclass by name.
        LinkageError ex = assertThrows(LinkageError.class,
                () -> t.transform(null, null, "sample/Sub", null, null, sub));
        assertTrue(ex.getMessage().contains("sample/Base"),
                "subclass failure must name its superclass: " + ex.getMessage());
    }

    @Test
    void abstractClassIsLeftAsIdentityClass() throws Exception {
        byte[] absBase = readResource("/sample/AbstractBase.class");
        byte[] absSub = readResource("/sample/AbstractSub.class");
        assertNotNull(absBase, "AbstractBase on classpath");
        assertNotNull(absSub, "AbstractSub on classpath");
        ClassFile cf = ClassFile.of();

        Config cfg = new Config();
        cfg.includes = Set.of("sample.AbstractBase", "sample.AbstractSub");
        cfg.excludes = Set.of();
        cfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        cfg.includesMode = Mode.INCLUDES_DEFAULT;
        ValueClassTransformer t = new ValueClassTransformer(cfg);

        // An abstract class is never converted: an agent-converted abstract
        // value class whose identity subclass loads later triggers a duplicate
        // class definition in the JVM, so abstract classes stay identity.
        assertTrue(ValueClassRewriter.suitabilityProblems(cf.parse(absBase), false, false)
                        .stream().anyMatch(p -> p.contains("abstract")),
                "an abstract class must be reported as not suitable");

        byte[] baseOut = t.transform(null, null, "sample/AbstractBase", null, null, absBase);
        assertNull(baseOut, "abstract class is left as an identity class");

        // Its concrete subclass now extends an identity class, so it is not a
        // value-class candidate either (a value class may extend only
        // java.lang.Object or java.lang.Record).
        assertTrue(ValueClassRewriter.suitabilityProblems(cf.parse(absSub), false, false)
                        .stream().anyMatch(p -> p.contains("extends the identity class sample/AbstractBase")),
                "AbstractSub extends the identity class AbstractBase, so it is not suitable");

        byte[] subOut = t.transform(null, null, "sample/AbstractSub", null, null, absSub);
        assertNull(subOut, "subclass of an identity abstract class is left as an identity class");
    }

    @Test
    void synchronizedBlockIsRejectedEvenWithIgnoreSynchronized() throws Exception {
        byte[] syncBlock = readResource("/sample/SyncBlock.class");
        assertNotNull(syncBlock, "SyncBlock on classpath");
        ClassFile cf = ClassFile.of();

        // monitorenter cannot be stripped, so the class is rejected even when
        // ignore-synchronized is set (which only strips ACC_SYNCHRONIZED).
        assertTrue(ValueClassRewriter.suitabilityProblems(cf.parse(syncBlock), false, true)
                        .stream().anyMatch(p -> p.contains("synchronized block")),
                "a synchronized block must be reported as not suitable");
        assertTrue(ValueClassRewriter.suitabilityProblems(cf.parse(syncBlock), true, true)
                        .stream().anyMatch(p -> p.contains("synchronized block")),
                "ignore-synchronized must not make a synchronized block suitable");
    }

    @Test
    void nonPrivateMutableFieldIsRejected() throws Exception {
        byte[] publicField = readResource("/sample/PublicField.class");
        assertNotNull(publicField, "PublicField on classpath");
        ClassFile cf = ClassFile.of();

        // A final class with a public mutable field can still be written by
        // sibling classes, so marking the field final would break them.
        assertTrue(ValueClassRewriter.suitabilityProblems(cf.parse(publicField), false, true)
                        .stream().anyMatch(p -> p.contains("non-private mutable field")),
                "a non-private mutable field must be reported as not suitable");
    }

    @Test
    void classWithoutInstanceFieldsIsRejected() throws Exception {
        byte[] noFields = readResource("/sample/NoFields.class");
        assertNotNull(noFields, "NoFields on classpath");
        ClassFile cf = ClassFile.of();

        assertTrue(ValueClassRewriter.suitabilityProblems(cf.parse(noFields), false, true)
                        .stream().anyMatch(p -> p.contains("no instance fields")),
                "a class with no instance fields must be reported as not suitable");
    }

    @Test
    void identityExceptionGuardInstrumentedIntoMonitorenterClasses() throws Exception {
        Path out = Files.createTempFile("auto-valhalla-identity", ".txt");
        out.toFile().deleteOnExit();

        // SyncBlock synchronizes on this, so it is selected but unsuitable; with
        // identity-exception-append-to set, its monitorenter is instrumented.
        Config cfg = new Config();
        cfg.includes = Set.of("sample.SyncBlock");
        cfg.excludes = Set.of();
        cfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        cfg.includesMode = EnumSet.of(Mode.SYNCHRONIZATION_MONITOR);
        cfg.synchronizationMonitorAppendTo = out.toString();
        ValueClassTransformer t = new ValueClassTransformer(cfg);

        byte[] syncBlock = readResource("/sample/SyncBlock.class");
        byte[] instrumented = t.transform(null, null, "sample/SyncBlock", null, null, syncBlock);
        assertNotNull(instrumented, "a selected class with a synchronized block is instrumented");

        ClassFile cf = ClassFile.of();
        assertTrue(cf.verify(instrumented).isEmpty(), "instrumented class must verify");
        boolean hasGuard = cf.parse(instrumented).methods().stream()
                .anyMatch(m -> m.code().map(c -> c.elementList().stream()
                        .anyMatch(e -> e instanceof InvokeInstruction ii
                                && ii.owner().asInternalName()
                                        .equals("io/github/thunkware/auto/valhalla/SynchronizationMonitor")
                                && ii.name().stringValue().equals("check")))
                        .orElse(false));
        assertTrue(hasGuard, "monitorenter must be preceded by SynchronizationMonitor.check");
    }

    @Test
    void onSuccessAppendToRecordsConvertedClassWithoutDuplicates() throws Exception {
        Path success = Files.createTempFile("auto-valhalla-success", ".txt");
        Path fail = Files.createTempFile("auto-valhalla-fail", ".txt");
        success.toFile().deleteOnExit();
        fail.toFile().deleteOnExit();

        // A name already present in the file must not be re-appended.
        Files.writeString(success, "sample.SampleX\n");

        Config cfg = new Config();
        cfg.includes = Set.of("sample.SampleX", "sample.Mutable");
        cfg.excludes = Set.of();
        cfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        cfg.includesMode = Mode.INCLUDES_DEFAULT;
        cfg.annotationOnFailAppendTo = fail.toString();
        cfg.annotationOnSuccessAppendTo = success.toString();
        cfg.includesOnFailAppendTo = fail.toString();
        cfg.includesOnSuccessAppendTo = success.toString();
        ValueClassTransformer t = new ValueClassTransformer(cfg);

        byte[] sampleX = readResource("/sample/SampleX.class");
        byte[] mutable = readResource("/sample/Mutable.class");
        assertNotNull(t.transform(null, null, "sample/SampleX", null, null, sampleX),
                "SampleX must be converted");
        assertNull(t.transform(null, null, "sample/Mutable", null, null, mutable),
                "Mutable must be left as an identity class");

        AsyncFileWriter.drain();
        assertEquals("sample.SampleX\n", Files.readString(success),
                "the pre-existing name must not be re-appended");
        assertTrue(Files.readString(fail).contains("sample.Mutable"),
                "the failing class must be recorded in the on-fail file");
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
        // yolo is a shorthand for the default includes-mode
        assertEquals(Mode.INCLUDES_DEFAULT, Mode.parse("yolo"));
        EnumSet<Mode> safeYolo = EnumSet.of(Mode.SAFE);
        safeYolo.addAll(Mode.INCLUDES_DEFAULT);
        assertEquals(safeYolo, Mode.parse("safe,yolo"));
        // the default annotation-mode set is safe (no mark-class-final)
        assertEquals(EnumSet.of(Mode.SAFE), Mode.ANNOTATION_DEFAULT);
    }

    @Test
    void parseModeThrowsOnUnknownTokens() {
        assertThrows(IllegalArgumentException.class, () -> Mode.parse("unknown-token"));
        assertThrows(IllegalArgumentException.class, () -> Mode.parse("safe,typo"));
        assertThrows(IllegalArgumentException.class,
                () -> Mode.parse("ignoresync")); // typo for ignore-synchronized
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Mode.parse("safe,xyz,abc"));
        assertTrue(ex.getMessage().contains("xyz"), "error should mention unknown token");
        assertTrue(ex.getMessage().contains("abc"), "error should mention unknown token");
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
        assertTrue(ValueClassTransformer.patternMatches(Set.of("*"), ClassName.of("any/pkg/Cls")));
        assertFalse(ValueClassTransformer.patternMatches(Set.of(), ClassName.of("any/pkg/Cls")));
    }

    @Test
    void onFailThrowIsPerSelectionSource() throws Exception {
        byte[] mutable = readResource("/sample/Mutable.class");
        assertNotNull(mutable, "Mutable on classpath");
        String internal = "sample/Mutable";

        // A mutable field written by a setter fails the mark-fields-final gate.
        // includes.on-fail-throw=false leaves it an identity class...
        Config quietCfg = new Config();
        quietCfg.includes = Set.of("sample.Mutable");
        quietCfg.excludes = Set.of();
        quietCfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        quietCfg.includesMode = Mode.INCLUDES_DEFAULT;
        ValueClassTransformer includesQuiet = new ValueClassTransformer(quietCfg);
        assertNull(includesQuiet.transform(null, null, internal, null, null, mutable),
                "includes.on-fail-throw=false leaves the class as identity");

        // ...while includes.on-fail-throw=true surfaces the rejection loudly
        // (an unloadable class file, never a usable value class).
        Config loudCfg = new Config();
        loudCfg.includes = Set.of("sample.Mutable");
        loudCfg.excludes = Set.of();
        loudCfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        loudCfg.includesMode = Mode.INCLUDES_DEFAULT;
        loudCfg.includesOnFail = OnFail.THROW;
        ValueClassTransformer includesLoud = new ValueClassTransformer(loudCfg);
        byte[] out = includesLoud.transform(null, null, internal, null, null, mutable);
        assertNotNull(out, "includes.on-fail-throw=true surfaces the rejection");
        assertFalse(DemoFixturesTest.isUsableValueClass(out),
                "a loud rejection is not a usable value class");

        // Annotation-selected classes default to loud: under the safe default
        // annotation-mode, a non-final annotated class is selected-but-not-
        // converted, which is a failure handled per the on-fail settings. The
        // loud annotation default must not come back as either a silent
        // identity class or a usable value class.
        byte[] mp = readResource("/demo5/broken/MutablePoint.class");
        assertNotNull(mp, "MutablePoint on classpath");
        Config annoCfg = new Config();
        annoCfg.includes = Set.of();
        annoCfg.excludes = Set.of();
        annoCfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        annoCfg.includesMode = Mode.INCLUDES_DEFAULT;
        annoCfg.annotationOnFail = OnFail.THROW;
        ValueClassTransformer annoLoud = new ValueClassTransformer(annoCfg);
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
        Config cfg = new Config();
        cfg.includes = Set.of();
        cfg.excludes = Set.of();
        cfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        cfg.includesMode = Mode.INCLUDES_DEFAULT;
        cfg.annotationOnFail = OnFail.THROW;
        cfg.includesOnFail = OnFail.THROW;
        ValueClassTransformer t = new ValueClassTransformer(cfg);
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
            Config bothCfg = new Config();
            bothCfg.includes = Set.of("demo5");
            bothCfg.excludes = Set.of();
            bothCfg.annotationMode = Mode.ANNOTATION_DEFAULT;
            bothCfg.includesMode = Mode.INCLUDES_DEFAULT;
            bothCfg.annotationOnFail = OnFail.THROW;
            bothCfg.annotationOnFailAppendTo = ann.getAbsolutePath();
            bothCfg.includesOnFail = OnFail.THROW;
            bothCfg.includesOnFailAppendTo = inc.getAbsolutePath();
            ValueClassTransformer both = new ValueClassTransformer(bothCfg);
            both.transform(null, null, "demo5/broken/MutablePoint", null, null, mp);
            AsyncFileWriter.drain();
            assertEquals("demo5.broken.MutablePoint\n", Files.readString(ann.toPath()),
                    "a both-selected class is appended to the annotation file as a class name");
            assertTrue(Files.readString(inc.toPath()).isEmpty(),
                    "the includes file is untouched when the annotation settings win");

            // Includes-only selection appends to the includes file.
            byte[] mutable = readResource("/sample/Mutable.class");
            File inc2 = File.createTempFile("inc2", ".log");
            try {
                Config incCfg = new Config();
                incCfg.includes = Set.of("sample.Mutable");
                incCfg.excludes = Set.of();
                incCfg.annotationMode = Mode.ANNOTATION_DEFAULT;
                incCfg.includesMode = Mode.INCLUDES_DEFAULT;
                incCfg.includesOnFailAppendTo = inc2.getAbsolutePath();
                ValueClassTransformer includesOnly = new ValueClassTransformer(incCfg);
                includesOnly.transform(null, null, "sample/Mutable", null, null, mutable);
                AsyncFileWriter.drain();
                assertEquals("sample.Mutable\n", Files.readString(inc2.toPath()),
                        "an includes-only class is appended to the includes file as a class name");
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
