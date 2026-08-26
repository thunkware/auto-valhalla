package io.github.thunkware.auto.valhalla.maven.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static XmlPlexusConfiguration config(String name, String value) {
        XmlPlexusConfiguration config = new XmlPlexusConfiguration("configuration");
        config.addChild(name, value);
        return config;
    }
}
