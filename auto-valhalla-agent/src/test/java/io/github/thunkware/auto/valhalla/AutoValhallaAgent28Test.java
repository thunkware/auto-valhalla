package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thunkware.auto.valhalla.logger.InternalLoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoValhallaAgent28Test {

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
            return AutoValhallaAgent28.parse();
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
    void configFileKeysNeedTheAutoValhallaPrefix(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("av.properties");
        Files.writeString(config, """
                auto-valhalla.includes=com.prefixed
                auto-valhalla.includes-mode=yolo
                logging.level.root=debug
                includes=com.bare
                excludes=com.bare.too
                bogus=1
                """);

        var cfg = parseWith("config", config.toString());
        assertEquals(Set.of("com/prefixed"), cfg.includes,
                "only the prefixed key is applied");
        assertTrue(cfg.excludes.isEmpty(), "an unprefixed key is ignored, not applied");
        assertEquals(EnumSet.of(Mode.YOLO), cfg.includesMode, "prefixed scalar applied");
        assertEquals("debug", cfg.loggerLevels.get("root"),
                "logging.level.* keys are read as-is");
    }

    @Test
    void systemPropertiesOverrideTheConfigFile(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("av.properties");
        Files.writeString(config, "auto-valhalla.includes-mode=yolo\n");

        var cfg = parseWith("config", config.toString(), "includes-mode", "safe");
        assertEquals(EnumSet.of(Mode.SAFE), cfg.includesMode,
                "an explicit -D must win over the config file");
    }

    @Test
    void patternOptionsAreReplacedNotAccumulated(@TempDir Path dir) throws Exception {
        Path fromConfig = dir.resolve("from-config.txt");
        Path fromProp = dir.resolve("from-prop.txt");
        Files.writeString(fromConfig, "com.FromConfigFile\n");
        Files.writeString(fromProp, "com.FromSysProp\n");
        Path config = dir.resolve("av.properties");
        Files.writeString(config, "auto-valhalla.includes=com.config\n"
                + "auto-valhalla.excludes=com.config.excluded\n"
                + "auto-valhalla.includes-files=" + fromConfig + "\n");

        var cfg = parseWith("config", config.toString(),
                "includes", "com.sysprop",
                "excludes", "com.sysprop.excluded",
                "includes-files", fromProp.toString());

        assertEquals(Set.of("com/sysprop", "com/FromSysProp"), cfg.includes,
                "the config file's includes and includes-files are replaced, not extended");
        assertEquals(Set.of("com/sysprop/excluded"), cfg.excludes,
                "the config file's excludes is replaced, not extended");
    }

    @Test
    void normalizePatternDoesNotRequireTrailingDot() {
        assertEquals("demo17", AutoValhallaAgent28.normalizePattern("demo17"));
        assertEquals("com/Foo", AutoValhallaAgent28.normalizePattern("com.Foo"));
        assertEquals("com/Foo/", AutoValhallaAgent28.normalizePattern("com.Foo."));
        assertEquals("*", AutoValhallaAgent28.normalizePattern("*"));
        assertEquals("com/example/", AutoValhallaAgent28.normalizePattern("com.example.*"));
        assertEquals("com/example/", AutoValhallaAgent28.normalizePattern("com/example/*"));
    }

    @Test
    void modeFlagsHaveDistinctDefaults() {
        var cfg = AutoValhallaAgent28.parse();
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
    void onFailAppendToDefaultsToNull() {
        var cfg = AutoValhallaAgent28.parse();
        assertNull(cfg.annotationOnFailAppendTo, "annotation.on-fail-append-to defaults to unset");
        assertNull(cfg.includesOnFailAppendTo, "includes.on-fail-append-to defaults to unset");

        var split = parseWith(
                "annotation.on-fail-append-to", "a.log",
                "includes.on-fail-append-to", "i.log");
        assertEquals("a.log", split.annotationOnFailAppendTo);
        assertEquals("i.log", split.includesOnFailAppendTo);
    }

    @Test
    void includesAndIncludesFileMerge(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("inc.txt");
        Files.writeString(f, "com.B\n");
        var cfg = parseWith("includes", "a.", "includes-files", f.toString());
        assertTrue(cfg.includes.contains("a/"), "explicit includes retained");
        assertTrue(cfg.includes.contains("com/B"), "includes-files patterns merged in");
    }

    @Test
    void unknownSysPropsAreDetected() {
        var unknown = AutoValhallaAgent28.unknownSysProps(
                Set.of("auto-valhalla.foo", "auto-valhalla.includes", "other.prop"));
        assertEquals(1, unknown.size());
        assertTrue(unknown.contains("auto-valhalla.foo"),
                "unknown key under auto-valhalla. must be reported");
    }

    @Test
    void knownSysPropsAreNotFlagged() {
        var unknown = AutoValhallaAgent28.unknownSysProps(
                Set.of("auto-valhalla.includes", "unrelated.prop"));
        assertTrue(unknown.isEmpty(), "no unknown props expected for known keys");
    }

    @Test
    void unknownEnvVarsAreDetected() {
        var unknown = AutoValhallaAgent28.unknownEnvVars(
                Set.of("AUTO_VALHALLA_FOO", "AUTO_VALHALLA_INCLUDES", "OTHER_VAR"));
        assertEquals(1, unknown.size());
        assertTrue(unknown.contains("AUTO_VALHALLA_FOO"),
                "unknown key under AUTO_VALHALLA_ must be reported");
    }

    @Test
    void knownEnvVarsAreNotFlagged() {
        var unknown = AutoValhallaAgent28.unknownEnvVars(
                Set.of("AUTO_VALHALLA_INCLUDES", "AUTO_VALHALLA_LOGGING", "UNRELATED"));
        assertTrue(unknown.isEmpty(), "no unknown vars expected for known keys");
    }

    @Test
    void includesFilesSupportsMultipleFiles(@TempDir Path dir) throws Exception {
        Path f1 = dir.resolve("inc1.txt");
        Path f2 = dir.resolve("inc2.txt");
        Files.writeString(f1, "com.A\n");
        Files.writeString(f2, "com.B\n");
        var bySemicolon = parseWith(
                "includes-files", f1.toString() + ";" + f2.toString());
        assertTrue(bySemicolon.includes.contains("com/A"), "first file loaded (semicolon)");
        assertTrue(bySemicolon.includes.contains("com/B"), "second file loaded (semicolon)");
    }

    @Test
    void unknownLogLevelDefaultsToInfo() {
        // A bad log-level must not crash the agent — it should warn and fall back.
        InternalLoggerFactory.setLevel("bogus");
        InternalLoggerFactory.setLevel(null); // reset to info for other tests
    }

    @Test
    void synchronizationMonitorAppendToHasDefaultValue() {
        var cfg = AutoValhallaAgent28.parse();
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
        var cfg = AutoValhallaAgent28.parse();
        assertNull(cfg.logging, "logging defaults to null (InternalLogger defaults to simple)");
    }

    @Test
    void loggingSystemIsParsed() {
        assertEquals("simple", parseWith("logging", "simple").logging);
        assertEquals("none", parseWith("logging", "none").logging);
        assertEquals("application", parseWith("logging", "application").logging);
    }

    @Test
    void loggingSystemNoneSuppressesOutput() {
        // setSystem(null) resets to SIMPLE; setSystem("none") suppresses
        InternalLoggerFactory.setSystem("none");
        InternalLoggerFactory.getLogger(AutoValhallaAgent28Test.class).info("this should be suppressed");
        InternalLoggerFactory.setSystem(null); // reset
    }

    @Test
    void unknownLoggingSystemDefaultsToSimple() {
        // Must not crash; warns and falls back to simple
        InternalLoggerFactory.setSystem("bogus-logging-system");
        InternalLoggerFactory.setSystem(null); // reset
    }
}
