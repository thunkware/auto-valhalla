package io.github.thunkware.auto.valhalla.maven.model;

public final class ProcessResult {

    public final int exit;
    public final String output;

    public ProcessResult(int exit, String output) {
        this.exit = exit;
        this.output = output;
    }
}
