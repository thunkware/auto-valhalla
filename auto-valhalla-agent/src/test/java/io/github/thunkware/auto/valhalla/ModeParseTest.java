package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModeParseTest {

    @Test
    void nullOrBlankYieldsDefault() {
        assertEquals(Mode.INCLUDES_DEFAULT, Mode.parse(null));
        assertEquals(Mode.INCLUDES_DEFAULT, Mode.parse("  "));
        assertEquals(Mode.ANNOTATION_DEFAULT, Mode.parse(null, Mode.ANNOTATION_DEFAULT));
        assertEquals(Mode.ANNOTATION_DEFAULT, Mode.parse("", Mode.ANNOTATION_DEFAULT));
    }

    @Test
    void safeToken() {
        assertEquals(EnumSet.of(Mode.SAFE), Mode.parse("safe", Mode.ANNOTATION_DEFAULT));
    }

    @Test
    void yoloExpandsToIncludesDefault() {
        Set<Mode> parsed = Mode.parse("yolo");
        assertEquals(Mode.INCLUDES_DEFAULT, parsed, "yolo must expand to INCLUDES_DEFAULT");
        assertFalse(parsed.contains(Mode.YOLO), "YOLO must not appear in the expanded set");
    }

    @Test
    void markClassFinalParsed() {
        assertEquals(EnumSet.of(Mode.MARK_CLASS_FINAL), Mode.parse("mark-class-final"));
    }

    @Test
    void separatorVariantsAreEquivalent() {
        Set<Mode> dash       = Mode.parse("mark-class-final");
        Set<Mode> underscore = Mode.parse("mark_class_final");
        Set<Mode> camel      = Mode.parse("markClassFinal");
        assertEquals(dash, underscore, "dash and underscore must produce the same set");
        assertEquals(dash, camel,      "dash and camelCase must produce the same set");
    }

    @Test
    void removeSynchronizedParsed() {
        assertEquals(EnumSet.of(Mode.REMOVE_SYNCHRONIZED), Mode.parse("remove-synchronized"));
    }

    @Test
    void markFieldsFinalParsed() {
        assertEquals(EnumSet.of(Mode.MARK_FIELDS_FINAL), Mode.parse("mark-fields-final"));
    }

    @Test
    void synchronizationMonitorAlone() {
        assertEquals(EnumSet.of(Mode.SYNCHRONIZATION_MONITOR),
                Mode.parse("synchronization-monitor"));
    }

    @Test
    void multipleModesCommaSeparated() {
        Set<Mode> modes = Mode.parse("mark-class-final,remove-synchronized");
        assertTrue(modes.contains(Mode.MARK_CLASS_FINAL));
        assertTrue(modes.contains(Mode.REMOVE_SYNCHRONIZED));
        assertEquals(2, modes.size());
    }

    @Test
    void synchronizationMonitorCombinedWithOtherModeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Mode.parse("synchronization-monitor,safe"));
    }

    @Test
    void unknownTokenThrows() {
        assertThrows(IllegalArgumentException.class, () -> Mode.parse("bogus-mode"));
    }

    @Test
    void toStringIsLowerCaseName() {
        assertEquals("safe",                   Mode.SAFE.toString());
        assertEquals("yolo",                   Mode.YOLO.toString());
        assertEquals("mark_class_final",       Mode.MARK_CLASS_FINAL.toString());
        assertEquals("remove_synchronized",    Mode.REMOVE_SYNCHRONIZED.toString());
        assertEquals("mark_fields_final",      Mode.MARK_FIELDS_FINAL.toString());
        assertEquals("synchronization_monitor", Mode.SYNCHRONIZATION_MONITOR.toString());
    }
}
