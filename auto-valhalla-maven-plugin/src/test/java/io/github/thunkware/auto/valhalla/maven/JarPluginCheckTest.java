package io.github.thunkware.auto.valhalla.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JarPluginCheckTest {

    @Test
    void versionsBefore340AreTooLow() {
        assertTrue(JarPluginCheck.versionTooLow("3.3.9"));
        assertTrue(JarPluginCheck.versionTooLow("3.3.9-beta"));
        assertTrue(JarPluginCheck.versionTooLow("3.3-alpha"));
        assertTrue(JarPluginCheck.versionTooLow("3"));
        assertTrue(JarPluginCheck.versionTooLow("2.5"));
    }

    @Test
    void versionsFrom340OnAreFine() {
        assertFalse(JarPluginCheck.versionTooLow("3.4.0"));
        assertFalse(JarPluginCheck.versionTooLow("3.4.1"));
        assertFalse(JarPluginCheck.versionTooLow("3.4"));
        assertFalse(JarPluginCheck.versionTooLow("3.5"));
        assertFalse(JarPluginCheck.versionTooLow("3.4.0-beta-1"));
        assertFalse(JarPluginCheck.versionTooLow("10.0.0"));
    }

    @Test
    void unrecognizedVersionsNeverWarn() {
        assertFalse(JarPluginCheck.versionTooLow("${jar.plugin.version}"));
        assertFalse(JarPluginCheck.versionTooLow("LATEST"));
        assertFalse(JarPluginCheck.versionTooLow(""));
    }
}
