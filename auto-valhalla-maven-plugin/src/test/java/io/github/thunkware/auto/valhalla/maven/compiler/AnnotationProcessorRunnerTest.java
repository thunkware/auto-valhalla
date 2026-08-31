package io.github.thunkware.auto.valhalla.maven.compiler;

import static com.google.common.base.Predicates.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thunkware.auto.valhalla.maven.support.FileTool;
import io.github.thunkware.auto.valhalla.processor.ProcessorTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AnnotationProcessorRunnerTest {

    @Test
    void testNotProcessorRefs() throws IOException {
        List<Path> files = FileTool.walkJavaFiles(Paths.get("."))
                .filter(not(this::isThisFile))
                .collect(Collectors.toList());
        assertTrue(files.size() > 1);
        files.forEach(file -> assertFalse(contentContainsProcessorName(file), file.toString()));
    }

    @Test
    void testProcessorRefs() throws IOException {
        // verify the plugin does not directly reference the processor so that the Annotations API classes are not
        // loaded by the plugin. In JDK8, that requires setting up tools.jar dependency, without which class load
        // exception would be thrown.
        List<Path> files = FileTool.walkJavaFiles(Paths.get("."))
                .filter(this::isThisFile)
                .collect(Collectors.toList());
        assertEquals(1, files.size());
        // ref to io.github.thunkware.auto.valhalla.processor.AutoValhallaProcessor for testing
        files.forEach(file -> assertTrue(contentContainsProcessorName(file), file.toString()));
    }

    private boolean contentContainsProcessorName(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new String(bytes, StandardCharsets.UTF_8).contains(ProcessorTool.PROCESSOR_NAME);
    }

    private boolean isThisFile(Path path) {
        if (path == null) {
            return false;
        }
        return path.getFileName().toString().equals(getClass().getSimpleName() + ".java");
    }

}
