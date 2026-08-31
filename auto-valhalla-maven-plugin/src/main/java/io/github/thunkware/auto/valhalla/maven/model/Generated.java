package io.github.thunkware.auto.valhalla.maven.model;

/**
 * A {@code GENERATED} manifest line: one selected type and the generated file
 * it lives in (relative to the selection out dir).
 */
public final class Generated {

    /**
     * Generated file path relative to the selection out dir.
     */
    public final String relativePath;

    public Generated(String relativePath) {
        this.relativePath = relativePath;
    }
}
