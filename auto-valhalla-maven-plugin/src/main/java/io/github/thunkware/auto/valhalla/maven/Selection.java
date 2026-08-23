package io.github.thunkware.auto.valhalla.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of a selection pass: which types were selected, which
 * selected types the processor could not generate (these fail the build),
 * where the generated copies are written, and the generated files grouped by
 * relative path for a follow-up compilation pass.
 */
final class Selection {

    public final List<String> selectedTypes = new ArrayList<>();
    public final Map<String, List<Generated>> generatedFiles = new LinkedHashMap<>();
    public final File generatedSources;

    Selection(File generatedSources) {
        this.generatedSources = generatedSources;
    }

    /**
     * Qualified names of the {@code @AutoValhalla}-annotated top-level
     * types the processor selected (and generated copies for).
     */
    public List<String> selectedTypes() {
        return selectedTypes;
    }

    /**
     * Directory holding the generated sources (the processor's out
     * dir); created even when nothing was generated.
     */
    public File generatedSources() {
        return generatedSources;
    }

    /**
     * Generated files by path relative to {@link #generatedSources()}; each
     * entry lists the selected types living in that file.
     */
    public Map<String, List<Generated>> generatedFiles() {
        return generatedFiles;
    }
}
