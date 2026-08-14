package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import java.io.File;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AutoValhallaAgentTest {

    /**
     * Sets system properties for the duration of a parse() call, then restores
     * the previous values (or clears if absent). Keys are bare option names
     * without the auto-valhalla. prefix; values alternate key, value, key, value.
     */
    private static Config parseWith(String... keyValues) {
        Map<String, String> saved = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            String prop = "auto-valhalla." + keyValues[i];
            saved.put(prop, System.getProperty(prop));
            System.setProperty(prop, keyValues[i + 1]);
        }
        try {
            return AutoValhallaAgent.parse();
        } finally {
            for (Map.Entry<String, String> e : saved.entrySet()) {
                if (e.getValue() == null) {
                    System.clearProperty(e.getKey());
                } else {
                    System.setProperty(e.getKey(), e.getValue());
                }
            }
        }
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
        var cfg = AutoValhallaAgent.parse();
        assertEquals(Mode.ANNOTATION_DEFAULT, cfg.annotationMode,
                "annotation-mode must default to safe");
        assertEquals(Mode.INCLUDES_DEFAULT, cfg.includesMode,
                "includes-mode must default to yolo");

        var safe = parseWith("includes-mode", "safe");
        assertEquals(EnumSet.of(Mode.SAFE), safe.includesMode);
        assertEquals(Mode.ANNOTATION_DEFAULT, safe.annotationMode,
                "annotation-mode keeps its own default");
    }

    @Test
    void onFailHasDistinctDefaults() {
        var cfg = AutoValhallaAgent.parse();
        assertEquals(OnFail.THROW, cfg.annotationOnFail,
                "annotation.on-fail must default to throw (loud for an explicit opt-in)");
        assertEquals(OnFail.DEBUG, cfg.includesOnFail,
                "includes.on-fail must default to debug (quiet for a broad sweep)");
        assertNull(cfg.annotationOnFailAppendTo, "annotation.on-fail-append-to defaults to unset");
        assertNull(cfg.includesOnFailAppendTo, "includes.on-fail-append-to defaults to unset");

        var split = parseWith(
                "annotation.on-fail", "warning",
                "includes.on-fail", "error",
                "annotation.on-fail-append-to", "a.log",
                "includes.on-fail-append-to", "i.log");
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
            var cfg = parseWith("includes", "a.", "includes-files", f.getAbsolutePath());
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
    void includesFilesSupportsMultipleFiles() throws Exception {
        File f1 = File.createTempFile("inc1", ".txt");
        File f2 = File.createTempFile("inc2", ".txt");
        Files.writeString(f1.toPath(), "com.A\n");
        Files.writeString(f2.toPath(), "com.B\n");
        try {
            var bySemicolon = parseWith(
                    "includes-files", f1.getAbsolutePath() + ";" + f2.getAbsolutePath());
            assertTrue(bySemicolon.includes.contains("com/A"), "first file loaded (semicolon)");
            assertTrue(bySemicolon.includes.contains("com/B"), "second file loaded (semicolon)");
        } finally {
            f1.delete();
            f2.delete();
        }
    }

    @Test
    void unknownLogLevelDefaultsToInfo() {
        // A bad log-level must not crash the agent — it should warn and fall back.
        InternalLogger.setLevel("bogus");
        InternalLogger.setLevel(null); // reset to info for other tests
    }

    @Test
    void onSuccessDefaultsToInfo() {
        var cfg = AutoValhallaAgent.parse();
        assertEquals(OnSuccess.INFO, cfg.annotationOnSuccess,
                "annotation.on-success must default to info");
        assertEquals(OnSuccess.INFO, cfg.includesOnSuccess,
                "includes.on-success must default to info");
    }

    @Test
    void onSuccessIsParsed() {
        var cfg = parseWith("annotation.on-success", "debug", "includes.on-success", "off");
        assertEquals(OnSuccess.DEBUG, cfg.annotationOnSuccess);
        assertEquals(OnSuccess.OFF, cfg.includesOnSuccess);
    }

    @Test
    void onFailOffIsParsed() {
        var cfg = parseWith("annotation.on-fail", "off", "includes.on-fail", "off");
        assertEquals(OnFail.OFF, cfg.annotationOnFail);
        assertEquals(OnFail.OFF, cfg.includesOnFail);
    }

    @Test
    void synchronizationMonitorLogLevelDefaultsToInfo() {
        var cfg = AutoValhallaAgent.parse();
        assertEquals(OnSuccess.INFO, cfg.synchronizationMonitorLogLevel,
                "synchronization-monitor.log-level must default to info");
    }

    @Test
    void synchronizationMonitorLogLevelIsParsed() {
        var cfg = parseWith("synchronization-monitor.log-level", "debug");
        assertEquals(OnSuccess.DEBUG, cfg.synchronizationMonitorLogLevel);
    }

    @Test
    void synchronizationMonitorAppendToHasDefaultValue() {
        var cfg = AutoValhallaAgent.parse();
        assertEquals("auto-valhalla.synchronization.txt", cfg.synchronizationMonitorAppendTo,
                "synchronization-monitor.append-to defaults to auto-valhalla.synchronization.txt");
    }

    @Test
    void synchronizationMonitorAppendToIsParsed() {
        var cfg = parseWith("synchronization-monitor.append-to", "custom.txt");
        assertEquals("custom.txt", cfg.synchronizationMonitorAppendTo);
    }

    @Test
    void synchronizationMonitorAppendToEmptyClearsDefault() {
        var cfg = parseWith("synchronization-monitor.append-to", "");
        assertNull(cfg.synchronizationMonitorAppendTo,
                "empty synchronization-monitor.append-to disables file writing (log-only mode)");
    }

    @Test
    void loggingDefaultsToNull() {
        var cfg = AutoValhallaAgent.parse();
        assertNull(cfg.logging, "logging defaults to null (InternalLogger defaults to simple)");
    }

    @Test
    void loggingModeIsParsed() {
        assertEquals("simple", parseWith("logging", "simple").logging);
        assertEquals("none", parseWith("logging", "none").logging);
        assertEquals("application", parseWith("logging", "application").logging);
    }

    @Test
    void loggingModeNoneSuppressesOutput() {
        // setMode(null) resets to SIMPLE; setMode("none") suppresses
        InternalLogger.setMode("none");
        InternalLogger.getLogger(AutoValhallaAgentTest.class).info("this should be suppressed");
        InternalLogger.setMode(null); // reset
    }

    @Test
    void unknownLoggingModeDefaultsToSimple() {
        // Must not crash; warns and falls back to simple
        InternalLogger.setMode("bogus-logging-mode");
        InternalLogger.setMode(null); // reset
    }
}
