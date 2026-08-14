package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import org.junit.jupiter.api.Test;

class SynchronizationInstrumenterTest {

    private byte[] read(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Test
    void syncBlockHasMonitorEnter() throws Exception {
        byte[] bytes = read("/sample/SyncBlock.class");
        assertNotNull(bytes, "SyncBlock.class must be on the test classpath");
        assertTrue(SynchronizationInstrumenter.hasMonitorEnter(ClassFile.of().parse(bytes)),
                "SyncBlock uses a synchronized block (monitorenter bytecode)");
    }

    @Test
    void synchronizedMethodHasNoMonitorEnter() throws Exception {
        byte[] bytes = read("/sample/Sync.class");
        assertNotNull(bytes, "Sync.class must be on the test classpath");
        assertFalse(SynchronizationInstrumenter.hasMonitorEnter(ClassFile.of().parse(bytes)),
                "Sync uses only ACC_SYNCHRONIZED methods; no monitorenter bytecode");
    }

    @Test
    void instrumentSyncBlockProducesVerifiableBytecode() throws Exception {
        byte[] bytes = read("/sample/SyncBlock.class");
        assertNotNull(bytes, "SyncBlock.class must be on the test classpath");
        ClassFile cf = ClassFile.of();
        byte[] out = SynchronizationInstrumenter.instrument(cf.parse(bytes), null);
        assertNotNull(out, "SyncBlock must be successfully instrumented");
        assertTrue(cf.verify(out).isEmpty(),
                "instrumented SyncBlock must pass bytecode verification");
    }
}
