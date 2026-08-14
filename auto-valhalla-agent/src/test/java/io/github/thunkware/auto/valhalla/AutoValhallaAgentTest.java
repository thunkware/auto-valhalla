package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class AutoValhallaAgentTest {

    @Test
    void prefixedAgentArgsAreHonored() {
        var cfg = AutoValhallaAgent.parse("auto-valhalla.includes=p.,auto-valhalla.log-level=debug");
        assertTrue(cfg.includes.contains("p/"), "includes must be honored via the auto-valhalla. prefix");
        assertEquals("debug", cfg.logLevel, "log-level must be honored via the auto-valhalla. prefix");
    }

    @Test
    void prefixedKeyIsTopLevelAssignment() {
        assertTrue(AutoValhallaAgent.isTopLevelAssignment("auto-valhalla.log-level=debug"));
        assertTrue(AutoValhallaAgent.isTopLevelAssignment("log-level=debug"));
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
    void modeFlagsHaveDistinctDefaults() {
        var cfg = AutoValhallaAgent.parse("includes=a.");
        assertEquals(Mode.ANNOTATION_DEFAULT, cfg.annotationMode,
                "annotation-mode must default to safe");
        assertEquals(Mode.INCLUDES_DEFAULT, cfg.includesMode,
                "includes-mode must default to yolo");
        var safe = AutoValhallaAgent.parse("includes-mode=safe");
        assertEquals(EnumSet.of(Mode.SAFE), safe.includesMode);
        assertEquals(Mode.ANNOTATION_DEFAULT, safe.annotationMode,
                "annotation-mode keeps its own default");
    }

    @Test
    void onFailFlagsHaveDistinctDefaults() {
        var cfg = AutoValhallaAgent.parse("includes=a.");
        assertTrue(cfg.annotationOnFailThrow,
                "annotation.on-fail-throw must default to true (loud for an explicit opt-in)");
        assertFalse(cfg.includesOnFailThrow,
                "includes.on-fail-throw must default to false (quiet for a broad sweep)");
        assertNull(cfg.annotationOnFailAppendTo, "annotation.on-fail-append-to defaults to unset");
        assertNull(cfg.includesOnFailAppendTo, "includes.on-fail-append-to defaults to unset");

        var split = AutoValhallaAgent.parse(
                "annotation.on-fail-throw=false,includes.on-fail-throw=true,"
                + "annotation.on-fail-append-to=a.log,includes.on-fail-append-to=i.log");
        assertFalse(split.annotationOnFailThrow);
        assertTrue(split.includesOnFailThrow);
        assertEquals("a.log", split.annotationOnFailAppendTo);
        assertEquals("i.log", split.includesOnFailAppendTo);
    }

    @Test
    void includesAndIncludesFileMerge() throws Exception {
        File f = File.createTempFile("inc", ".txt");
        Files.writeString(f.toPath(), "com.B\n");
        try {
            var cfg = AutoValhallaAgent.parse("includes=a.,includes-files=" + f.getAbsolutePath());
            assertTrue(cfg.includes.contains("a/"), "explicit includes retained");
            assertTrue(cfg.includes.contains("com/B"), "includes-files patterns merged in");
        } finally {
            f.delete();
        }
    }
}
