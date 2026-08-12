package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class AutoValhallaAgentTest {

    @Test
    void prefixedAgentArgsAreHonored() {
        var cfg = AutoValhallaAgent.parse("auto-valhalla.includes=p.,auto-valhalla.debug=true");
        assertTrue(cfg.includes().contains("p/"), "includes must be honored via the auto-valhalla. prefix");
        assertTrue(cfg.debug(), "debug must be honored via the auto-valhalla. prefix");
    }

    @Test
    void prefixedKeyIsTopLevelAssignment() {
        assertTrue(AutoValhallaAgent.isTopLevelAssignment("auto-valhalla.debug=true"));
        assertTrue(AutoValhallaAgent.isTopLevelAssignment("debug=true"));
        assertFalse(AutoValhallaAgent.isTopLevelAssignment("foo=true"));
    }

    @Test
    void bareWordPatternIsPackagePrefix() {
        assertEquals("demo16/", AutoValhallaAgent.normalizePattern("demo16"));
        assertEquals("com/Foo", AutoValhallaAgent.normalizePattern("com.Foo"));
        assertEquals("com/Foo/", AutoValhallaAgent.normalizePattern("com.Foo."));
        assertEquals("*", AutoValhallaAgent.normalizePattern("*"));
        assertEquals("com/example/", AutoValhallaAgent.normalizePattern("com.example.*"));
        assertEquals("com/example/", AutoValhallaAgent.normalizePattern("com/example/*"));
    }

    @Test
    void modeDefaultsToIgnoreStar() {
        var cfg = AutoValhallaAgent.parse("includes=a.");
        assertEquals(Mode.getDefaultModes(), cfg.mode(),
                "mode must default to ignore-non-final,ignore-synchronized");
        var safe = AutoValhallaAgent.parse("mode=safe");
        assertEquals(EnumSet.of(Mode.SAFE), safe.mode());
    }

    @Test
    void includesAndIncludesFileMerge() throws Exception {
        File f = File.createTempFile("inc", ".txt");
        Files.writeString(f.toPath(), "com.B\n");
        try {
            var cfg = AutoValhallaAgent.parse("includes=a.,includes-file=" + f.getAbsolutePath());
            assertTrue(cfg.includes().contains("a/"), "explicit includes retained");
            assertTrue(cfg.includes().contains("com/B"), "includes-file patterns merged in");
        } finally {
            f.delete();
        }
    }
}
