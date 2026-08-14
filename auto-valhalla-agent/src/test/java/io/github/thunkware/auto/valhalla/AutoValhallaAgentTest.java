package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Set;
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
    void onFailHasDistinctDefaults() {
        var cfg = AutoValhallaAgent.parse("includes=a.");
        assertEquals(OnFail.THROW, cfg.annotationOnFail,
                "annotation.on-fail must default to throw (loud for an explicit opt-in)");
        assertEquals(OnFail.DEBUG, cfg.includesOnFail,
                "includes.on-fail must default to debug (quiet for a broad sweep)");
        assertNull(cfg.annotationOnFailAppendTo, "annotation.on-fail-append-to defaults to unset");
        assertNull(cfg.includesOnFailAppendTo, "includes.on-fail-append-to defaults to unset");

        var split = AutoValhallaAgent.parse(
                "annotation.on-fail=warning,includes.on-fail=error,"
                + "annotation.on-fail-append-to=a.log,includes.on-fail-append-to=i.log");
        assertEquals(OnFail.WARNING, split.annotationOnFail);
        assertEquals(OnFail.ERROR, split.includesOnFail);
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

    @Test
    void unknownSysPropsAreDetected() {
        var unknown = AutoValhallaAgent.unknownSysProps(
                Set.of("auto-valhalla.foo", "auto-valhalla.includes", "other.prop"));
        assertEquals(1, unknown.size());
        assertTrue(unknown.contains("auto-valhalla.foo"),
                "unknown key under auto-valhalla. must be reported");
    }

    @Test
    void knownSysPropsAreNotFlagged() {
        var unknown = AutoValhallaAgent.unknownSysProps(
                Set.of("auto-valhalla.includes", "auto-valhalla.log-level", "unrelated.prop"));
        assertTrue(unknown.isEmpty(), "no unknown props expected for known keys");
    }

    @Test
    void unknownEnvVarsAreDetected() {
        var unknown = AutoValhallaAgent.unknownEnvVars(
                Set.of("AUTO_VALHALLA_FOO", "AUTO_VALHALLA_INCLUDES", "OTHER_VAR"));
        assertEquals(1, unknown.size());
        assertTrue(unknown.contains("AUTO_VALHALLA_FOO"),
                "unknown key under AUTO_VALHALLA_ must be reported");
    }

    @Test
    void knownEnvVarsAreNotFlagged() {
        var unknown = AutoValhallaAgent.unknownEnvVars(
                Set.of("AUTO_VALHALLA_INCLUDES", "AUTO_VALHALLA_LOG_LEVEL", "UNRELATED"));
        assertTrue(unknown.isEmpty(), "no unknown vars expected for known keys");
    }

    @Test
    void unknownAgentArgIsIgnoredGracefully() {
        var cfg = AutoValhallaAgent.parse("foo=bar,includes=a.");
        assertTrue(cfg.includes.contains("a/"), "known arg still applied despite unknown arg");
    }

    @Test
    void includesFilesSupportsMultipleFiles() throws Exception {
        File f1 = File.createTempFile("inc1", ".txt");
        File f2 = File.createTempFile("inc2", ".txt");
        Files.writeString(f1.toPath(), "com.A\n");
        Files.writeString(f2.toPath(), "com.B\n");
        try {
            var bySemicolon = AutoValhallaAgent.parse(
                    "includes-files=" + f1.getAbsolutePath() + ";" + f2.getAbsolutePath());
            assertTrue(bySemicolon.includes.contains("com/A"), "first file loaded (semicolon)");
            assertTrue(bySemicolon.includes.contains("com/B"), "second file loaded (semicolon)");

            var byRepeat = AutoValhallaAgent.parse(
                    "includes-files=" + f1.getAbsolutePath() + ",includes-files=" + f2.getAbsolutePath());
            assertTrue(byRepeat.includes.contains("com/A"), "first file loaded (repeated option)");
            assertTrue(byRepeat.includes.contains("com/B"), "second file loaded (repeated option)");
        } finally {
            f1.delete();
            f2.delete();
        }
    }
}
