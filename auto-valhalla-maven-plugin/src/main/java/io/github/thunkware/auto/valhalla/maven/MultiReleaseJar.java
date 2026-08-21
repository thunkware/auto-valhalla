package io.github.thunkware.auto.valhalla.maven;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Makes an existing jar a multi-release jar by adding
 * {@code Multi-Release: true} to its manifest, preserving every other entry
 * byte-for-byte.
 *
 * <p>Invoked on the jar after {@code maven-jar-plugin} has built it: the JVM
 * only honors {@code META-INF/versions} content when the manifest carries the
 * {@code Multi-Release: true} attribute, and {@code maven-jar-plugin} does not
 * add it automatically.
 *
 * <p>The jar is rewritten in place through a temporary sibling followed by an
 * atomic move, so a failed run never leaves a truncated jar behind.
 */
public final class MultiReleaseJar {

    private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";
    private static final String ENTRY = "Multi-Release";

    private MultiReleaseJar() {
    }

    /**
     * Rewrites {@code jar} so its manifest declares {@code Multi-Release: true}.
     * If the jar already carries the attribute, it is left untouched.
     *
     * @param jar the jar file to rewrite in place
     * @return {@code true} if the manifest was updated, {@code false} if the
     *         jar already declared {@code Multi-Release: true}
     * @throws IOException if the jar cannot be read or rewritten
     */
    public static boolean addMultiReleaseFlag(File jar) throws IOException {
        Map<String, Content> entries = readEntries(jar);
        byte[] manifestBytes;
        if (entries.containsKey(MANIFEST_PATH)) {
            byte[] existing = entries.remove(MANIFEST_PATH).bytes;
            Manifest manifest = new Manifest(new ByteArrayInputStream(existing));
            if (Boolean.TRUE.toString().equalsIgnoreCase(
                    manifest.getMainAttributes().getValue(ENTRY))) {
                return false;
            }
            manifest.getMainAttributes().putValue(ENTRY, Boolean.TRUE.toString());
            manifestBytes = toBytes(manifest);
        } else {
            manifestBytes = freshManifest();
        }
        writeJar(jar, manifestBytes, entries);
        return true;
    }

    private static Map<String, Content> readEntries(File jar) throws IOException {
        Map<String, Content> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jar.toPath())))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                byte[] out = Utils.toByteArray(zin);
                entries.put(entry.getName(), new Content(entry.getMethod(), out));
            }
        }
        return entries;
    }

    private static void writeJar(File jar, byte[] manifestBytes, Map<String, Content> entries)
            throws IOException {
        File parent = jar.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        File tmp = null;
        try {
            tmp = File.createTempFile(jar.getName() + ".", ".tmp", parent);
            try (ZipOutputStream zout = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp.toPath())))) {
                ZipEntry manifest = new ZipEntry(MANIFEST_PATH);
                manifest.setMethod(ZipEntry.STORED);
                manifest.setSize(manifestBytes.length);
                manifest.setCompressedSize(manifestBytes.length);
                manifest.setCrc(crc(manifestBytes));
                zout.putNextEntry(manifest);
                zout.write(manifestBytes);
                zout.closeEntry();
                for (Map.Entry<String, Content> e : entries.entrySet()) {
                    Content content = e.getValue();
                    ZipEntry entry = new ZipEntry(e.getKey());
                    entry.setMethod(content.isStored ? ZipEntry.STORED : ZipEntry.DEFLATED);
                    if (entry.getMethod() == ZipEntry.STORED) {
                        entry.setSize(content.bytes.length);
                        entry.setCompressedSize(content.bytes.length);
                        entry.setCrc(crc(content.bytes));
                    }
                    zout.putNextEntry(entry);
                    zout.write(content.bytes);
                    zout.closeEntry();
                }
            }
            Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp.toPath());
            }
        }
    }

    private static byte[] freshManifest() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue(ENTRY, Boolean.TRUE.toString());
        return toBytes(manifest);
    }

    private static byte[] toBytes(Manifest manifest) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manifest.write(out);
        out.write('\n');
        return out.toByteArray();
    }

    private static long crc(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return crc32.getValue();
    }

    private static final class Content {
        final boolean isStored;
        final byte[] bytes;

        Content(int method, byte[] bytes) {
            if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                throw new IllegalArgumentException("invalid compression method: " + method);
            }
            this.isStored = method == ZipEntry.STORED;
            this.bytes = bytes;
        }
    }
}
