package io.github.thunkware.auto.valhalla.maven.support;

public final class StringTool {

    private StringTool() {
        throw new AssertionError();
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

    public static String plural(int n) {
        return n <= 1 ? "" : "s";
    }
}
