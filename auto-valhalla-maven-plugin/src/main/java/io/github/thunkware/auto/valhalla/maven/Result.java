package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of a run: which types were selected, how many were
 * converted and which selected types javac rejected. The
 * annotation-selected failures fail the build.
 */
public final class Result {

    final List<String> annotationFailures = new ArrayList<>();
    final List<String> selected = new ArrayList<>();
    File generatedSources;
    int converted;

    Result() {
    }

    public int convertedCount() {
        return converted;
    }

    public List<String> annotationFailures() {
        return annotationFailures;
    }

    /**
     * Qualified names of the {@code @AutoValhalla}-annotated top-level
     * types the processor selected (and generated copies for).
     */
    public List<String> selectedTypes() {
        return selected;
    }

    /**
     * Directory holding the generated sources (the processor's out
     * dir), or {@code null} when nothing was generated.
     */
    public File generatedSources() {
        return generatedSources;
    }
}
