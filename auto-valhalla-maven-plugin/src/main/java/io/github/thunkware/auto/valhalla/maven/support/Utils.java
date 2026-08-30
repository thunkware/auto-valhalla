package io.github.thunkware.auto.valhalla.maven.support;

public final class Utils {

    private Utils() {
        throw new AssertionError();
    }

    public static boolean asBoolean(Boolean value) {
        if (value == null) {
            return false;
        }
        return value;
    }
}
