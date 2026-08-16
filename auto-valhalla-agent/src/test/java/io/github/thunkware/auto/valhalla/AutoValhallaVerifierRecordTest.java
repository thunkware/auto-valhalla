package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.api.AutoValhallaVerifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoValhallaVerifierRecordTest {

    record SimpleRecord(int x, int y) {}

    record EmptyRecord() {}

    record SyncRecord(int x) {
        public synchronized int syncGet() {
            return x;
        }
    }

    @Test
    void recordPassesSafeMode() {
        AutoValhallaVerifier.safe().verify(SimpleRecord.class);
    }

    @Test
    void recordViolationsIsEmpty() {
        assertTrue(AutoValhallaVerifier.safe().violations(SimpleRecord.class).isEmpty());
    }

    @Test
    void recordSuperclassDoesNotTriggerExtendsViolation() {
        // records extend java.lang.Record; this must not appear as "extends identity class"
        List<String> v = AutoValhallaVerifier.safe().violations(SimpleRecord.class);
        assertTrue(
                v.stream().noneMatch(s -> s.contains("extends")),
                "extending Record must not be flagged as an identity-class extension");
    }

    @Test
    void emptyRecordIsRejected() {
        List<String> v = AutoValhallaVerifier.safe().violations(EmptyRecord.class);
        assertEquals(1, v.size());
        assertTrue(v.getFirst().contains("no instance fields"));
    }

    @Test
    void recordWithSynchronizedMethodIsRejected() {
        List<String> v = AutoValhallaVerifier.safe().violations(SyncRecord.class);
        assertEquals(1, v.size());
        assertTrue(v.getFirst().contains("synchronized instance method(s)"));
        assertTrue(v.getFirst().contains("syncGet"));
    }

    @Test
    void recordWithSynchronizedMethodPassesWithRemoveSynchronized() {
        AutoValhallaVerifier.safe().removeSynchronized().verify(SyncRecord.class);
    }

    @Test
    void multipleRecordsAllChecked() {
        // SimpleRecord passes; EmptyRecord has one violation
        List<String> v =
                AutoValhallaVerifier.safe().violations(SimpleRecord.class, EmptyRecord.class);
        assertEquals(1, v.size());
        assertTrue(v.getFirst().startsWith(EmptyRecord.class.getName() + ": "));
    }
}
