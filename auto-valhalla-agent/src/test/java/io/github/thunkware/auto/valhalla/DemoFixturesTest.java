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
 * <p>Before the assertions were added, a regression in demo5.Point (a
 * non-final field also written by a setter) made the class fail to transform
 * while run-demo.sh still reported success.
 */
class DemoFixturesTest {

    @Test
    void demoFixturesAreRewrittenByDemoConfig() throws Exception {
        ClassFile cf = ClassFile.of();
        // Mirrors run-demo.sh: includes=demo16,demo5, includes-mode=yolo, and
        // the default failure handling (annotation throws, includes stay quiet).
        ValueClassTransformer transformer = new ValueClassTransformer(
                Set.of("demo16", "demo5"), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, true, null, false, null);

        // Point is selected by BOTH @AutoValhalla and includes, so the effective
        // mode set is the union, which includes mark-fields-final. Its fields are
        // already final, so it must still be rewritten.
        byte[] point = transformer.transform(null, null, "demo5/Point", null, null,
                readResource("/demo5/Point.class"));
        assertNotNull(point, "demo5.Point must be rewritten under the demo config"
                + " (annotation + includes-mode=yolo)");
        assertTrue(cf.verify(point).isEmpty(), "rewritten demo5.Point must verify");

        // Square relies on includes-mode=yolo marking its non-final `side` field
        // (written exactly once, in the constructor) final.
        byte[] square = transformer.transform(null, null, "demo5/Square", null, null,
                readResource("/demo5/Square.class"));
        assertNotNull(square, "demo5.Square must be rewritten with includes-mode=yolo");
        assertTrue(cf.verify(square).isEmpty(), "rewritten demo5.Square must verify");

        // Circle is the annotation-selected analogue of Square: non-final `radius`
        // written exactly once, in the constructor. Under the demo config it is
        // selected by both sources, so it must be rewritten too.
        byte[] circle = transformer.transform(null, null, "demo5/Circle", null, null,
                readResource("/demo5/Circle.class"));
        assertNotNull(circle, "demo5.Circle must be rewritten under the demo config");
        assertTrue(cf.verify(circle).isEmpty(), "rewritten demo5.Circle must verify");
    }

    @Test
    void annotatedMutableClassIsRejectedByDemoConfig() throws Exception {
        // MutablePoint is @AutoValhalla, but its non-final `y` is reassigned by
        // setY() outside the constructor, so it can never be a JEP 401 value
        // class. The demo config selects it by both sources; the annotation
        // settings win, so the default annotation.on-fail-throw=true makes the
        // rejection loud instead of silently rewriting it.
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
        ValueClassTransformer loud = new ValueClassTransformer(
                Set.of("demo16", "demo5"), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, true, null, false, null);
        byte[] loudOut = loud.transform(null, null, internal, null, null, original);
        assertNotNull(loudOut, "annotation.on-fail-throw=true surfaces the rejection");
        assertFalse(isUsableValueClass(loudOut),
                "an annotated mutable class must never come back as a usable value class");

        // With throwing disabled for both sources it is instead left as an
        // identity class -- still never rewritten into a value class.
        ValueClassTransformer quiet = new ValueClassTransformer(
                Set.of("demo16", "demo5"), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, false, null, false, null);
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
        ValueClassTransformer strict = new ValueClassTransformer(
                Set.of("demo5.broken.SyncPoint"), Set.of(),
                Mode.ANNOTATION_DEFAULT,
                EnumSet.of(Mode.MARK_CLASS_FINAL, Mode.MARK_FIELDS_FINAL),
                false, false, null, false, null);
        assertNull(strict.transform(null, null, internal, null, null, original),
                "a synchronized immutable class is left as identity without ignore-synchronized");
    }

    @Test
    void annotatedPointIsRewrittenByAnnotationSelectionAlone() throws Exception {
        // With no includes selection, Point must still convert purely through
        // @AutoValhalla under the default annotation-mode.
        ValueClassTransformer transformer = new ValueClassTransformer(
                Set.of(), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, true, null, false, null);
        byte[] point = transformer.transform(null, null, "demo5/Point", null, null,
                readResource("/demo5/Point.class"));
        assertNotNull(point, "@AutoValhalla alone must convert demo5.Point");
        assertTrue(ClassFile.of().verify(point).isEmpty(),
                "annotation-rewritten demo5.Point must verify");
    }

    @Test
    void annotatedCircleIsRewrittenByAnnotationSelectionAlone() throws Exception {
        // Like Square, Circle's `radius` is non-final yet written exactly once,
        // in the constructor; unlike Square it is selected by @AutoValhalla.
        // The default annotation-mode has no mark-fields-final, but the field is
        // safe to mark final, so annotation selection alone must convert it.
        ValueClassTransformer transformer = new ValueClassTransformer(
                Set.of(), Set.of(),
                Mode.ANNOTATION_DEFAULT, Mode.INCLUDES_DEFAULT,
                false, true, null, false, null);
        byte[] circle = transformer.transform(null, null, "demo5/Circle", null, null,
                readResource("/demo5/Circle.class"));
        assertNotNull(circle, "@AutoValhalla alone must convert demo5.Circle");
        assertTrue(ClassFile.of().verify(circle).isEmpty(),
                "annotation-rewritten demo5.Circle must verify");
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
