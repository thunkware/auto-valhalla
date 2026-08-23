package io.github.thunkware.auto.valhalla.maven;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class Utils {

    private Utils() {
        throw new AssertionError();
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

    public static boolean isNotBlank(String s) {
        return !trim(s).isEmpty();
    }

    public static String trim(String in) {
        return in == null ? "" : in.trim();
    }

    public static boolean asBoolean(Boolean value) {
        if (value == null) {
            return false;
        }
        return value;
    }

    public static String normalizeEncoding(String encoding) {
        return isNotBlank(encoding) ? trim(encoding) : "UTF-8";
    }

    public static String plural(int n) {
        return n <= 1 ? "" : "es";
    }
}
