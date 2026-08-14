package io.github.thunkware.auto.valhalla;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class AutoValhallaVerifierTest {

    // valid: final, extends Object, has private final fields, no synchronized methods
    static final class GoodPoint {
        private final int x;
        private final int y;

        GoodPoint(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    // not final
    static class NonFinalPoint {
        private final int x;

        NonFinalPoint(int x) {
            this.x = x;
        }
    }

    // final, but has a synchronized instance method (static synchronized is fine)
    static final class SynchronizedPoint {
        private final int x;

        SynchronizedPoint(int x) {
            this.x = x;
        }

        public synchronized int get() {
            return x;
        }

        public static synchronized void staticSync() {}
    }

    // no instance fields
    static final class NoInstanceFields {
        static final int CONSTANT = 0;
    }

    // non-private mutable instance field
    static final class PublicMutableField {
        public int x;

        PublicMutableField(int x) {
            this.x = x;
        }
    }

    // extends an identity class (not Object or Record)
    static final class ExtendsIdentity extends NonFinalPoint {
        private final int z;

        ExtendsIdentity(int x, int z) {
            super(x);
            this.z = z;
        }
    }

    // not final + synchronized instance method: two violations
    static class MultiViolation {
        private final int x;

        MultiViolation(int x) {
            this.x = x;
        }

        public synchronized int get() {
            return x;
        }
    }

    @Test
    public void safePassesCompliantClass() {
        AutoValhallaVerifier.safe().verify(GoodPoint.class);
    }

    @Test
    public void violationsEmptyForCompliantClass() {
        assertTrue(AutoValhallaVerifier.safe().violations(GoodPoint.class).isEmpty());
    }

    @Test
    public void safeRejectsNonFinalClass() {
        List<String> v = AutoValhallaVerifier.safe().violations(NonFinalPoint.class);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("not final"));
    }

    @Test
    public void markClassFinalAcceptsNonFinalClass() {
        AutoValhallaVerifier.safe().markClassFinal().verify(NonFinalPoint.class);
    }

    @Test
    public void safeRejectsSynchronizedInstanceMethod() {
        List<String> v = AutoValhallaVerifier.safe().violations(SynchronizedPoint.class);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("synchronized instance method(s)"));
        assertTrue(v.get(0).contains("get"));
    }

    @Test
    public void staticSynchronizedMethodIsIgnored() {
        // SynchronizedPoint has a static synchronized method; only instance sync triggers a violation
        AutoValhallaVerifier.safe().removeSynchronized().verify(SynchronizedPoint.class);
    }

    @Test
    public void removeSynchronizedAcceptsSynchronizedClass() {
        AutoValhallaVerifier.safe().removeSynchronized().verify(SynchronizedPoint.class);
    }

    @Test
    public void noInstanceFieldsIsRejected() {
        List<String> v = AutoValhallaVerifier.safe().violations(NoInstanceFields.class);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("no instance fields"));
    }

    @Test
    public void nonPrivateMutableFieldIsRejected() {
        List<String> v = AutoValhallaVerifier.safe().violations(PublicMutableField.class);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("non-private mutable"));
        assertTrue(v.get(0).contains("x"));
    }

    @Test
    public void extendsIdentityClassIsRejected() {
        // ExtendsIdentity is already final, so the only violation is the non-Object/Record superclass
        List<String> v = AutoValhallaVerifier.safe().violations(ExtendsIdentity.class);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("extends"));
    }

    @Test
    public void interfaceIsRejected() {
        List<String> v = AutoValhallaVerifier.safe().violations(Runnable.class);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("interface"));
    }

    @Test
    public void enumIsRejected() {
        List<String> v = AutoValhallaVerifier.safe().violations(Thread.State.class);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("enum"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void verifyThrowsOnViolation() {
        AutoValhallaVerifier.safe().verify(NonFinalPoint.class);
    }

    @Test
    public void exceptionMessageListsAllViolations() {
        try {
            AutoValhallaVerifier.safe().verify(MultiViolation.class);
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("not final"));
            assertTrue(ex.getMessage().contains("synchronized"));
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    @Test
    public void violationMessageIncludesClassName() {
        List<String> v = AutoValhallaVerifier.safe().violations(NonFinalPoint.class);
        assertTrue(v.get(0).startsWith(NonFinalPoint.class.getName() + ": "));
    }

    @Test
    public void multipleViolationsAllReported() {
        List<String> v = AutoValhallaVerifier.safe().violations(MultiViolation.class);
        assertEquals(2, v.size()); // not final + synchronized method
    }

    @Test
    public void multipleClassesAllChecked() {
        List<String> v =
                AutoValhallaVerifier.safe().violations(NonFinalPoint.class, SynchronizedPoint.class);
        assertEquals(2, v.size()); // one violation each
    }

    @Test
    public void builderMethodsReturnNewInstance() {
        ConfiguredVerifier base = AutoValhallaVerifier.safe();
        ConfiguredVerifier withMcf = base.markClassFinal();
        ConfiguredVerifier withRs = base.removeSynchronized();
        assertNotSame(base, withMcf);
        assertNotSame(base, withRs);
        // base is still strict — NonFinalPoint has a violation
        assertFalse(base.violations(NonFinalPoint.class).isEmpty());
        // withMcf accepts it
        assertTrue(withMcf.violations(NonFinalPoint.class).isEmpty());
    }
}
