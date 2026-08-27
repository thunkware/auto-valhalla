# test-maven-plugin-remove-annotation

Exercises the `auto-valhalla-maven-plugin`'s `generate-sources` goal with
`removeAnnotation` set to `true`: the `@AutoValhalla` marker is stripped from
the generated value-class sources (the base classes keep it), which are then
compiled under `META-INF/versions/28/`.

The test asserts the generated source contains no `@AutoValhalla` annotation
while the value keyword is still inserted, and that the versioned class is a
value class (major 72).