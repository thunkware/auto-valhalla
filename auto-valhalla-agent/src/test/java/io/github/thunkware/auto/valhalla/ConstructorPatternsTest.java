package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration coverage for {@link ConstructorRewriter}: ordinary constructors that
 * the hand-rolled stack model must not under-run (and therefore silently drop as
 * identity classes). Each fixture is selected the way annotation/includes are
 * (mark-class-final + remove-synchronized) and must rewrite and verify.
 */
class ConstructorPatternsTest {

    private byte[] read(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private void assertConverts(String internal) throws Exception {
        byte[] original = read("/" + internal + ".class");
        assertNotNull(original, internal + " must be on the test classpath");
        ClassFile cf = ClassFile.of();
        var model = cf.parse(original);
        assertTrue(ValueClassRewriter.isSuitable(model, true, true),
                internal + " must be suitable (mark-class-final + remove-synchronized)");
        byte[] out = ValueClassRewriter.transform(model, true, true, null);
        assertNotNull(out, internal + " must be rewritten into a value class");
        var outModel = cf.parse(out);
        assertTrue(ValueClassRewriter.alreadyValue(outModel),
                internal + " must encode a value class");
        assertTrue(cf.verify(out).isEmpty(), internal + " must verify");
    }

    private void assertStaysIdentity(String internal) throws Exception {
        byte[] original = read("/" + internal + ".class");
        assertNotNull(original, internal + " must be on the test classpath");
        ClassFile cf = ClassFile.of();
        var model = cf.parse(original);
        assertTrue(ValueClassRewriter.isSuitable(model, true, true),
                internal + " must be structurally suitable");
        byte[] out = ValueClassRewriter.transform(model, true, true, null);
        assertNull(out, internal
                + " must be left as an identity class (relocating its constructor"
                + " would produce illegal early-phase bytecode)");
    }

    @Test
    void chainedAssignmentStaysIdentity() throws Exception {
        // a = b = 5 compiles to dup_x1 on `this`; moving it before super() would
        // use an uninitialized `this`, so the strict-init verifier rejects it and
        // the class is safely left as an identity class.
        assertStaysIdentity("sample/Chained");
    }

    @Test
    void arrayLengthInitializer() throws Exception {
        assertConverts("sample/ArrayLen");
    }

    @Test
    void widenDoubleToLong() throws Exception {
        assertConverts("sample/Widen");
    }

    @Test
    void newInConstructor() throws Exception {
        // this.list = new ArrayList<>(): the new/dup/invokespecial operate on a
        // freshly allocated object, so the initializer relocates cleanly.
        assertConverts("sample/NewList");
    }

    @Test
    void stringConcatIndy() throws Exception {
        assertConverts("sample/Concat");
    }

    /**
     * A rewritten constructor must keep doing everything the original did.
     * Verifying the bytecode is not enough: code left over between the last
     * relocated field store and the end of the body used to be emitted after the
     * return, where it verifies but never runs.
     */
    @Test
    void constructorSideEffectsSurviveRelocation(@TempDir Path dir) throws Exception {
        String internal = "sample/SideEffect";
        byte[] original = read("/" + internal + ".class");
        assertNotNull(original, internal + " must be on the test classpath");

        ClassFile cf = ClassFile.of();
        byte[] out = ValueClassRewriter.transform(cf.parse(original), true, true, null);
        assertNotNull(out, internal + " must be rewritten into a value class");
        assertTrue(ValueClassRewriter.alreadyValue(cf.parse(out)),
                internal + " must encode a value class");
        assertTrue(cf.verify(out).isEmpty(), internal + " must verify");

        // Load the rewritten class in an isolated loader and run it: the
        // constructor must set the field AND call bump().
        Path pkg = Files.createDirectories(dir.resolve("sample"));
        Files.write(pkg.resolve("SideEffect.class"), out);
        try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() }, null)) {
            Class<?> cls = loader.loadClass("sample.SideEffect");
            Object instance = cls.getConstructor(int.class).newInstance(7);
            assertEquals(7, cls.getMethod("x").invoke(instance),
                    "the relocated field store must still assign the field");
            assertEquals(1, cls.getField("calls").getInt(null),
                    "the constructor's trailing bump() call must still run");
        }
    }
}
