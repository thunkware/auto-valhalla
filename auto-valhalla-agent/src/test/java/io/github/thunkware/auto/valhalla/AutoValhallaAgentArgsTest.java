package io.github.thunkware.auto.valhalla;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutoValhallaAgentArgsTest {

    @Test
    void multiValueIncludesKeptAsOneToken() {
        List<String> tokens = AutoValhallaAgent.splitAgentArgs("includes=com.a.Foo,com.b.Bar");
        assertEquals(List.of("includes=com.a.Foo,com.b.Bar"), tokens);
    }

    @Test
    void separateAssignmentsSplitOnTopLevelCommas() {
        List<String> tokens = AutoValhallaAgent.splitAgentArgs("mode,includes=com.a.Foo");
        assertEquals(List.of("mode", "includes=com.a.Foo"), tokens);
    }

    @Test
    void configValueWithCommasIsPreserved() {
        List<String> tokens = AutoValhallaAgent.splitAgentArgs("config=key=a,b;c");
        assertEquals(List.of("config=key=a,b;c"), tokens);
    }
}
