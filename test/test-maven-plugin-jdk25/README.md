# test-maven-plugin-jdk25

Exercises the `auto-valhalla-maven-plugin` when the **base classes are
compiled at release 25** (no preview). The plugin generates value-class
variants under `META-INF/versions/28/`.

The test asserts the versioned class is a value class (major 72) while the
base class stays an identity class at major version 69 (Java 25).
