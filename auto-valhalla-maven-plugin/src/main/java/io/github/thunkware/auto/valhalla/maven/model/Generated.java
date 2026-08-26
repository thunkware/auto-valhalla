package io.github.thunkware.auto.valhalla.maven.model;

/**
 * A {@code GENERATED} manifest line: one selected type and the generated file
 * it lives in (relative to the selection out dir).
 */
public final class Generated {

    public final String qname;
    public final String rel;

    public Generated(String qname, String rel) {
        this.qname = qname;
        this.rel = rel;
    }

    /**
     * Fully qualified name of the selected type.
     */
    public String qname() {
        return qname;
    }

    /**
     * Generated file path relative to the selection out dir.
     */
    public String rel() {
        return rel;
    }
}
