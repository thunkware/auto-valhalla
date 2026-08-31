package io.github.thunkware.auto.valhalla.maven.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.junit.jupiter.api.Test;

class ConfigEvaluatorTest {

    @Test
    void supportsAllConfigurationOrigins() {
        XmlPlexusConfiguration nested = config("value", "nested");
        XmlPlexusConfiguration project = config("value", "project");

        assertEquals("nested", ConfigEvaluator.of(nested, project, "nestedFirst")
                .resolveString("value"));
        assertEquals("project", ConfigEvaluator.of(nested, project, "projectFirst")
                .resolveString("value"));
        assertEquals("nested", ConfigEvaluator.of(nested, project, "nestedOnly")
                .resolveString("value"));
        assertEquals("project", ConfigEvaluator.of(nested, project, "projectOnly")
                .resolveString("value"));
    }

    @Test
    void rejectsUnknownConfigurationOrigin() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigEvaluator.of(null, null, "invalid"));
    }

    @Test
    void configurationsFollowOriginOrderAndDropNulls() {
        XmlPlexusConfiguration nested = config("value", "nested");
        XmlPlexusConfiguration project = config("value", "project");

        assertEquals(Arrays.asList(nested, project),
                ConfigEvaluator.of(nested, project, "nestedFirst").configurations());
        assertEquals(Arrays.asList(project, nested),
                ConfigEvaluator.of(nested, project, "projectFirst").configurations());
        assertEquals(Collections.singletonList(nested),
                ConfigEvaluator.of(nested, null, "nestedFirst").configurations());
        assertEquals(Collections.singletonList(project),
                ConfigEvaluator.of(null, project, "projectFirst").configurations());
        assertEquals(Collections.emptyList(),
                ConfigEvaluator.of(null, null, "nestedFirst").configurations());
    }

    private static XmlPlexusConfiguration config(String name, String value) {
        XmlPlexusConfiguration config = new XmlPlexusConfiguration("configuration");
        config.addChild(name, value);
        return config;
    }
}
