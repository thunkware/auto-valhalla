package io.github.thunkware.auto.valhalla.processor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProcessorToolTest {

    @Test
    void test() {
        assertEquals(ProcessorTool.PROCESSOR_NAME, AutoValhallaProcessor.class.getName());
    }
}
