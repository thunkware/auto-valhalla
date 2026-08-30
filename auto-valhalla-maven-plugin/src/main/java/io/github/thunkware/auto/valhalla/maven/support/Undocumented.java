package io.github.thunkware.auto.valhalla.maven.support;

public final class Undocumented {

    private Undocumented() {
        throw new AssertionError();
    }

    public static boolean undocumented(String name) {
        return Boolean.getBoolean("auto-valhalla.undocumented" + name);
    }
}
