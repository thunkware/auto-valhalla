package io.github.thunkware.auto.valhalla.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JarPluginCheckerTest {

    @Test
    void versionsBefore340AreTooLow() {
        assertTrue(JarPluginChecker.versionTooLow("3.3.9"));
        assertTrue(JarPluginChecker.versionTooLow("3.3.9-beta"));
        assertTrue(JarPluginChecker.versionTooLow("3.3-alpha"));
        assertTrue(JarPluginChecker.versionTooLow("3"));
        assertTrue(JarPluginChecker.versionTooLow("2.5"));
    }

    @Test
    void versionsFrom340OnAreFine() {
        assertFalse(JarPluginChecker.versionTooLow("3.4.0"));
        assertFalse(JarPluginChecker.versionTooLow("3.4.1"));
        assertFalse(JarPluginChecker.versionTooLow("3.4"));
        assertFalse(JarPluginChecker.versionTooLow("3.5"));
        assertFalse(JarPluginChecker.versionTooLow("3.4.0-beta-1"));
        assertFalse(JarPluginChecker.versionTooLow("10.0.0"));
    }

    @Test
    void unrecognizedVersionsNeverWarn() {
        assertFalse(JarPluginChecker.versionTooLow("${jar.plugin.version}"));
        assertFalse(JarPluginChecker.versionTooLow("LATEST"));
        assertFalse(JarPluginChecker.versionTooLow(""));
    }
}
