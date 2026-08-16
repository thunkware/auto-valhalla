package io.github.thunkware.auto.valhalla;

public final class AutoValhallaAttachAgent {

    private AutoValhallaAttachAgent() {
    }

    public static void attach() {
        AutoValhallaAgent.attach();
    }

    public static boolean isSupported() {
        return AutoValhallaAgent.isSupported();
    }
}
