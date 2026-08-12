package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import org.junit.jupiter.api.Test;

/**
 * Integration coverage for {@link ConstructorRewriter}: ordinary constructors that
 * the hand-rolled stack model must not under-run (and therefore silently drop as
 * identity classes). Each fixture is selected the way annotation/includes are
 * (ignore-non-final + ignore-synchronized) and must rewrite and verify.
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
                internal + " must be suitable (ignore-non-final + ignore-synchronized)");
        byte[] out = ValueClassRewriter.transform(model, false, true, true);
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
        byte[] out = ValueClassRewriter.transform(model, false, true, true);
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
}
