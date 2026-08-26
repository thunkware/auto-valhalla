package io.github.thunkware.auto.valhalla.maven.support;

/** Selects which Maven compiler configuration sources are evaluated. */
public enum ConfigOrigin {
    NESTED_FIRST,
    PROJECT_FIRST,
    NESTED_ONLY,
    PROJECT_ONLY
}
