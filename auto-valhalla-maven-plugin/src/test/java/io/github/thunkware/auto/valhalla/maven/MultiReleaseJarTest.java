package io.github.thunkware.auto.valhalla.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies {@link MultiReleaseJar} rewrites the manifest without touching
 *  every other entry. */
class MultiReleaseJarTest {

    @TempDir
    File tempDir;

    @Test
    void addsFlagPreservingEntries() throws Exception {
        byte[] classBytes = "fake-class-bytes".getBytes(StandardCharsets.UTF_8);
        File jar = new File(tempDir, "app.jar");
        writeJar(jar, "Manifest-Version: 1.0\nCreated-By: test\n\n",
                Map.of("a/b/C.class", classBytes));

        assertTrue(MultiReleaseJar.addMultiReleaseFlag(jar));

        Map<String, byte[]> after = readJar(jar);
        assertTrue(after.containsKey("META-INF/MANIFEST.MF"));
        Manifest manifest = new Manifest(new ByteArrayInputStream(after.get("META-INF/MANIFEST.MF")));
        assertEquals("true", manifest.getMainAttributes().getValue("Multi-Release"));
        // Created-By is preserved
        assertEquals("test", manifest.getMainAttributes().getValue("Created-By"));
        assertTrue(after.containsKey("a/b/C.class"));
        assertTrue(java.util.Arrays.equals(classBytes, after.get("a/b/C.class")),
                "content entry bytes must be preserved");
    }

    @Test
    void createsManifestWhenMissing() throws Exception {
        File jar = new File(tempDir, "nomanifest.jar");
        writeJar(jar, null, Map.of("C.class", new byte[] { 1, 2, 3 }));

        assertTrue(MultiReleaseJar.addMultiReleaseFlag(jar));

        Map<String, byte[]> after = readJar(jar);
        assertTrue(after.containsKey("META-INF/MANIFEST.MF"));
        Manifest manifest = new Manifest(new ByteArrayInputStream(after.get("META-INF/MANIFEST.MF")));
        assertEquals("true", manifest.getMainAttributes().getValue("Multi-Release"));
        assertTrue(after.containsKey("C.class"));
    }

    @Test
    void reportsNoChangeWhenAlreadyMultiRelease() throws Exception {
        File jar = new File(tempDir, "mralready.jar");
        writeJar(jar, "Manifest-Version: 1.0\nMulti-Release: true\n\n", Map.of());

        assertFalse(MultiReleaseJar.addMultiReleaseFlag(jar));
    }

    private static void writeJar(File jar, String manifest, Map<String, byte[]> entries)
            throws IOException {
        try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(jar.toPath()))) {
            if (manifest != null) {
                zout.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                zout.write(manifest.getBytes(StandardCharsets.UTF_8));
                zout.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }
    }

    private static Map<String, byte[]> readJar(File jar) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(jar.toPath())))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = zin.read(buffer)) != -1) {
                    bos.write(buffer, 0, n);
                }
                out.put(entry.getName(), bos.toByteArray());
            }
        }
        return out;
    }
}