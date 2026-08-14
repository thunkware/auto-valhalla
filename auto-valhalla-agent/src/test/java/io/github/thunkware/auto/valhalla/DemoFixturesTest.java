package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rewrites the real demo5 fixtures (genuine Java 5 bytecode) with the exact
 * configuration run-demo.sh uses, so a demo class that silently stops
 * transforming fails the agent's unit tests instead of only the demo.
 *
 * <p>Before the assertions were added, a regression in demo5.annotation.Point (a
 * non-final field also written by a setter) made the class fail to transform
 * while run-demo.sh still reported success.
 */
class DemoFixturesTest {

    @Test
    void demoFixturesAreRewrittenByDemoConfig() throws Exception {
        ClassFile cf = ClassFile.of();
        // Mirrors run-demo.sh: includes=demo16.includes.,demo5.includes.,
        // annotation-mode=yolo, includes-mode=yolo, and the default failure
        // handling (annotation throws, includes stay quiet).
        Set<Mode> yolo = EnumSet.of(Mode.MARK_CLASS_FINAL,
                Mode.IGNORE_SYNCHRONIZED, Mode.MARK_FIELDS_FINAL);
        Config cfg = new Config();
        cfg.includes = Set.of("demo16.includes.", "demo5.includes.");
        cfg.excludes = Set.of();
        cfg.annotationMode = yolo;
        cfg.includesMode = yolo;
        cfg.annotationOnFailThrow = true;
        ValueClassTransformer transformer = new ValueClassTransformer(cfg);

        // Point is selected only by @AutoValhalla (it is in demo5.annotation,
        // outside the demo5.includes. prefix). annotation-mode=yolo marks its
        // non-final `x` field final, so it must be rewritten.
        byte[] point = transformer.transform(null, null, "demo5/annotation/Point", null, null,
                readResource("/demo5/annotation/Point.class"));
        assertNotNull(point, "demo5.annotation.Point must be rewritten under the demo config"
                + " (annotation-mode=yolo)");
        assertTrue(cf.verify(point).isEmpty(), "rewritten demo5.annotation.Point must verify");

        // Square relies on includes-mode=yolo marking its non-final `side` field
        // (written exactly once, in the constructor) final.
        byte[] square = transformer.transform(null, null, "demo5/includes/Square", null, null,
                readResource("/demo5/includes/Square.class"));
        assertNotNull(square, "demo5.includes.Square must be rewritten with includes-mode=yolo");
        assertTrue(cf.verify(square).isEmpty(), "rewritten demo5.includes.Square must verify");

        // Circle is the un-annotated analogue of Square: non-final `radius`
        // written exactly once, in the constructor. Selected via the
        // demo5.includes. prefix, it must be rewritten by includes-mode=yolo.
        byte[] circle = transformer.transform(null, null, "demo5/includes/Circle", null, null,
                readResource("/demo5/includes/Circle.class"));
        assertNotNull(circle, "demo5.includes.Circle must be rewritten under the demo config");
        assertTrue(cf.verify(circle).isEmpty(), "rewritten demo5.includes.Circle must verify");
    }

    @Test
    void annotatedMutableClassIsRejectedByDemoConfig() throws Exception {
        // MutablePoint is @AutoValhalla, but its non-final `y` is reassigned by
        // setY() outside the constructor, so it can never be a JEP 401 value
        // class. Under the demo config's annotation-mode=yolo it is selected by
        // the annotation; the annotation settings win, so the default
        // annotation.on-fail-throw=true makes the rejection loud instead of
        // silently rewriting it.
        String internal = "demo5/broken/MutablePoint";
        byte[] original = readResource("/demo5/broken/MutablePoint.class");
        assertNotNull(original, "demo5.broken.MutablePoint must be on the test classpath");

        ClassFile cf = ClassFile.of();
        var model = cf.parse(original);
        assertTrue(ValueClassRewriter.hasAutoValhallaAnnotation(model),
                "MutablePoint is annotated");
        assertFalse(ValueClassRewriter.fieldsSafeToMarkFinal(model),
                "a field reassigned outside the constructor cannot be marked final");

        // Default failure handling: annotated classes fail loudly, so the
        // rejection returns an unloadable class file -- never a usable value
        // class.
        Set<Mode> yolo = EnumSet.of(Mode.MARK_CLASS_FINAL,
                Mode.IGNORE_SYNCHRONIZED, Mode.MARK_FIELDS_FINAL);
        Config loudCfg = new Config();
        loudCfg.includes = Set.of();
        loudCfg.excludes = Set.of();
        loudCfg.annotationMode = yolo;
        loudCfg.includesMode = yolo;
        loudCfg.annotationOnFailThrow = true;
        ValueClassTransformer loud = new ValueClassTransformer(loudCfg);
        byte[] loudOut = loud.transform(null, null, internal, null, null, original);
        assertNotNull(loudOut, "annotation.on-fail-throw=true surfaces the rejection");
        assertFalse(isUsableValueClass(loudOut),
                "an annotated mutable class must never come back as a usable value class");

        // With throwing disabled for both sources it is instead left as an
        // identity class -- still never rewritten into a value class.
        Config quietCfg = new Config();
        quietCfg.includes = Set.of();
        quietCfg.excludes = Set.of();
        quietCfg.annotationMode = yolo;
        quietCfg.includesMode = yolo;
        quietCfg.annotationOnFailThrow = false;
        ValueClassTransformer quiet = new ValueClassTransformer(quietCfg);
        assertNull(quiet.transform(null, null, internal, null, null, original),
                "without on-fail-throw the annotated mutable class is left as identity");
    }

    @Test
    void immutableButSynchronizedFixtureIsRejectedWithoutIgnoreSynchronized()
            throws Exception {
        // SyncPoint is immutable (all fields final, written only in the
        // constructor) yet has synchronized instance methods, which a value class
        // cannot declare. It lives in demo5.broken precisely because it is never
        // a value-class candidate without ignore-synchronized.
        String internal = "demo5/broken/SyncPoint";
        byte[] original = readResource("/demo5/broken/SyncPoint.class");
        assertNotNull(original, "demo5.broken.SyncPoint must be on the test classpath");

        ClassFile cf = ClassFile.of();
        var model = cf.parse(original);
        // Immutable fields are not enough: the synchronised methods make it
        // unsuitable, and the targeted message names exactly those methods.
        assertTrue(ValueClassRewriter.fieldsSafeToMarkFinal(model),
                "SyncPoint fields are immutable");
        List<String> problems = ValueClassRewriter.suitabilityProblems(model, false, true);
        assertTrue(problems.stream().anyMatch(p -> p.contains("synchronized instance method(s) sum, dot")),
                "the synchronized problem must name the methods: " + problems);

        // A strict config (mark-class-final + mark-fields-final, no
        // ignore-synchronized) selects it but must leave it as an identity class,
        // never an unusable value class.
        Config cfg = new Config();
        cfg.includes = Set.of("demo5.broken.SyncPoint");
        cfg.excludes = Set.of();
        cfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        cfg.includesMode = EnumSet.of(Mode.MARK_CLASS_FINAL, Mode.MARK_FIELDS_FINAL);
        ValueClassTransformer strict = new ValueClassTransformer(cfg);
        assertNull(strict.transform(null, null, internal, null, null, original),
                "a synchronized immutable class is left as identity without ignore-synchronized");
    }

    @Test
    void annotatedPointIsRejectedBySafeAnnotationDefault() throws Exception {
        // Point is selected by @AutoValhalla but is not final. The default
        // annotation-mode is safe, which converts only already-final (or
        // abstract) classes, so Point is selected-but-not-converted: a failure
        // handled by the on-fail settings. The loud annotation default
        // (annotation.on-fail-throw=true) surfaces a broken class, never a
        // usable value class.
        Config cfg = new Config();
        cfg.includes = Set.of();
        cfg.excludes = Set.of();
        cfg.annotationMode = Mode.ANNOTATION_DEFAULT;
        cfg.includesMode = Mode.INCLUDES_DEFAULT;
        cfg.annotationOnFailThrow = true;
        ValueClassTransformer transformer = new ValueClassTransformer(cfg);
        byte[] point = transformer.transform(null, null, "demo5/annotation/Point", null, null,
                readResource("/demo5/annotation/Point.class"));
        assertNotNull(point, "default annotation-mode (safe) rejects non-final demo5.annotation.Point loudly");
        assertFalse(isUsableValueClass(point),
                "the safe-default rejection of demo5.annotation.Point is not a usable value class");
    }

    @Test
    void annotatedPointIsRewrittenWhenMarkClassFinalOptsIn() throws Exception {
        // The same class converts once the user explicitly opts into
        // mark-class-final (the old annotation default), even without includes.
        Config cfg = new Config();
        cfg.includes = Set.of();
        cfg.excludes = Set.of();
        cfg.annotationMode = EnumSet.of(Mode.MARK_CLASS_FINAL, Mode.IGNORE_SYNCHRONIZED);
        cfg.includesMode = Mode.INCLUDES_DEFAULT;
        cfg.annotationOnFailThrow = true;
        ValueClassTransformer transformer = new ValueClassTransformer(cfg);
        byte[] point = transformer.transform(null, null, "demo5/annotation/Point", null, null,
                readResource("/demo5/annotation/Point.class"));
        assertNotNull(point, "@AutoValhalla + mark-class-final must convert demo5.annotation.Point");
        assertTrue(ClassFile.of().verify(point).isEmpty(),
                "annotation-rewritten demo5.annotation.Point must verify");
    }

    /** True if the bytes parse as a value class and pass verification. */
    static boolean isUsableValueClass(byte[] bytes) {
        try {
            ClassFile cf = ClassFile.of();
            return ValueClassRewriter.alreadyValue(cf.parse(bytes))
                    && cf.verify(bytes).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] readResource(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
