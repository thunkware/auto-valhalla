package io.github.thunkware.auto.valhalla.maven.support;

import static io.github.thunkware.auto.valhalla.maven.support.StringTool.isNotBlank;
import static io.github.thunkware.auto.valhalla.maven.support.StringTool.trim;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FileTool {

    private FileTool() {
        throw new AssertionError();
    }

    public static Stream<Path> walk(Path start) throws IOException {
        return walk(start, path -> true);
    }

    public static Stream<Path> walkJavaFiles(Path start) throws IOException {
        return walk(start, path -> path.toString().endsWith(".java"));
    }

    public static Stream<Path> walk(Path start, Predicate<Path> filter) throws IOException {
        try (Stream<Path> stream = Files.walk(start)) {
            return stream.filter(filter)
                    .collect(Collectors.toList())
                    .stream();
        }
    }

    public static byte[] toByteArray(final InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    public static String normalizeEncoding(String encoding) {
        return isNotBlank(encoding) ? trim(encoding) : "UTF-8";
    }

}
