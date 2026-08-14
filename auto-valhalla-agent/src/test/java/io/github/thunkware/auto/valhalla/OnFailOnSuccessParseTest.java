package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OnFailOnSuccessParseTest {

    // --- OnFail ---

    @Test
    void onFailParseAllValues() {
        assertEquals(OnFail.THROW,   OnFail.parse("throw",   OnFail.DEBUG));
        assertEquals(OnFail.ERROR,   OnFail.parse("error",   OnFail.DEBUG));
        assertEquals(OnFail.WARNING, OnFail.parse("warning", OnFail.DEBUG));
        assertEquals(OnFail.WARNING, OnFail.parse("warn",    OnFail.DEBUG));
        assertEquals(OnFail.INFO,    OnFail.parse("info",    OnFail.DEBUG));
        assertEquals(OnFail.DEBUG,   OnFail.parse("debug",   OnFail.THROW));
        assertEquals(OnFail.OFF,     OnFail.parse("off",     OnFail.THROW));
    }

    @Test
    void onFailIsCaseInsensitive() {
        assertEquals(OnFail.THROW,   OnFail.parse("THROW",   OnFail.DEBUG));
        assertEquals(OnFail.WARNING, OnFail.parse("WARNING", OnFail.DEBUG));
        assertEquals(OnFail.OFF,     OnFail.parse("OFF",     OnFail.DEBUG));
    }

    @Test
    void onFailNullOrBlankYieldsDefault() {
        assertEquals(OnFail.THROW, OnFail.parse(null,  OnFail.THROW));
        assertEquals(OnFail.DEBUG, OnFail.parse("",    OnFail.DEBUG));
        assertEquals(OnFail.INFO,  OnFail.parse("   ", OnFail.INFO));
    }

    @Test
    void onFailUnknownYieldsDefault() {
        assertEquals(OnFail.WARNING, OnFail.parse("bogus", OnFail.WARNING));
    }

    // --- OnSuccess ---

    @Test
    void onSuccessParseAllValues() {
        assertEquals(OnSuccess.INFO,  OnSuccess.parse("info",  OnSuccess.OFF));
        assertEquals(OnSuccess.DEBUG, OnSuccess.parse("debug", OnSuccess.OFF));
        assertEquals(OnSuccess.OFF,   OnSuccess.parse("off",   OnSuccess.INFO));
    }

    @Test
    void onSuccessIsCaseInsensitive() {
        assertEquals(OnSuccess.INFO,  OnSuccess.parse("INFO",  OnSuccess.OFF));
        assertEquals(OnSuccess.DEBUG, OnSuccess.parse("DEBUG", OnSuccess.OFF));
        assertEquals(OnSuccess.OFF,   OnSuccess.parse("OFF",   OnSuccess.INFO));
    }

    @Test
    void onSuccessNullOrBlankYieldsDefault() {
        assertEquals(OnSuccess.INFO,  OnSuccess.parse(null,  OnSuccess.INFO));
        assertEquals(OnSuccess.DEBUG, OnSuccess.parse("",    OnSuccess.DEBUG));
        assertEquals(OnSuccess.OFF,   OnSuccess.parse("  ",  OnSuccess.OFF));
    }

    @Test
    void onSuccessUnknownYieldsDefault() {
        assertEquals(OnSuccess.INFO, OnSuccess.parse("verbose", OnSuccess.INFO));
    }
}
