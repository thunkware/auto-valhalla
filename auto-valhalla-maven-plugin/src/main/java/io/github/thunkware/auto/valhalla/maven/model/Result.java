package io.github.thunkware.auto.valhalla.maven.model;

import java.io.File;

/**
 * The outcome of a run: which types were selected, how many were
 * converted and which selected types javac rejected. The
 * annotation-selected failures fail the build.
 */
public final class Result {

    public Selection selection;
    public File versionsDirectory;
    public int converted;
}
