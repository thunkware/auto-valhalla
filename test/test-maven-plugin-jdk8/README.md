# test-maven-plugin-jdk8

Exercises the `auto-valhalla-maven-plugin` when `$JAVA_HOME=JDK8`. The plugin generates value-class
variants under `META-INF/versions/28/` using `$JAVA28_HOME`

The test asserts the versioned class is a value class (major 72) while the
base class stays an identity class at major version 52 (Java 8).
