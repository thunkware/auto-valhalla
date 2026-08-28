![stable](https://img.shields.io/badge/stability-experimental-orange.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.thunkware/auto-valhalla-api)](https://central.sonatype.com/artifact/io.github.thunkware/auto-valhalla-api)
[![javadoc](https://javadoc.io/badge2/io.github.thunkware/auto-valhalla-api/javadoc.svg)](https://javadoc.io/doc/io.github.thunkware/auto-valhalla-api)

# auto-valhalla api

The `@AutoValhalla` annotation. 

If using the maven plugin, use the annotation to mark ordinary identity classes for transformation at build time.

If using the javaagent, you may use the annotation or you may configure the agent's `-includes` flag.

For an overview of the whole project, see the [auto-valhalla README](../README.md).

## Quickstart

Add the dependency and annotate the classes you want converted:

```xml
<dependency>
  <groupId>io.github.thunkware</groupId>
  <artifactId>auto-valhalla-api</artifactId>
  <version>0.2.0</version>
</dependency>
```

```java
import io.github.thunkware.auto.valhalla.api.AutoValhalla;

@AutoValhalla
public final class Point {
    public final int x;        // with final instance fields
    public final int y;
    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }
}

@AutoValhalla
public record Currency(String code) { }  // or a record
```

Then either configure the maven plugin or the javaagent.

## Verify at build time

If using javaagent, you want also want to use `AutoValhallaVerifier` to check the structural prerequisites at build time,
before you ever run the agent:

```java
import io.github.thunkware.auto.valhalla.api.AutoValhallaVerifier;

@Test
void test() {
    AutoValhallaVerifier.safe().verify(Point.class, Currency.class);
}
```

* `safe().verify(classes)` — throws `IllegalArgumentException` listing every
  class that would be rejected.
* `safe().violations(classes)` — returns the violations as a `List<String>`
  instead of throwing.

Extra modes can be enabled exactly like the agent's:

```java
AutoValhallaVerifier.safe()
    .removeSynchronized()
    .markClassFinal()
    .markFieldsFinal()
    .verify(Foo.class, Bar.class);
```

## Compatibility

The annotation and verifier are compiled as Java 1.5 class files, so they can be
added to a JDK 1.5 (or later) codebase without raising its compile version to
JDK 28.

## API

* `io.github.thunkware.auto.valhalla.api.AutoValhalla` — marks a class for
  conversion.
* `io.github.thunkware.auto.valhalla.api.AutoValhallaVerifier` — build-time
  structural checks, with `markClassFinal()`, `markFieldsFinal()` and
  `removeSynchronized()` builder methods.
* `io.github.thunkware.auto.valhalla.api.ConfiguredVerifier` — the configured
  verifier returned by `AutoValhallaVerifier.safe()` and friends.
