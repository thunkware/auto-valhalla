![stable](https://img.shields.io/badge/stability-experimental-orange.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.thunkware/auto-valhalla-api)](https://central.sonatype.com/artifact/io.github.thunkware/auto-valhalla-api)
[![javadoc](https://javadoc.io/badge2/io.github.thunkware/auto-valhalla-api/javadoc.svg)](https://javadoc.io/doc/io.github.thunkware/auto-valhalla-api)

# auto-valhalla api

The `@AutoValhalla` annotation and `AutoValhallaVerifier`. This is the only
artifact your application code needs to depend on to opt in to value-class
transformation.

For the agent that performs the transformation, see the
[auto-valhalla-agent project](../auto-valhalla-agent). For an overview of the
whole project, see the [auto-valhalla README](../README.md).

## Quickstart

Add the dependency and annotate the classes you want converted:

```xml
<dependency>
  <groupId>io.github.thunkware</groupId>
  <artifactId>auto-valhalla-api</artifactId>
  <version>0.1.1-SNAPSHOT</version>
</dependency>
```

```java
import io.github.thunkware.auto.valhalla.api.AutoValhalla;

@AutoValhalla
public final class Point {     // class must be final
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

Then run the application on JDK 28 with `--enable-preview` and the agent;
without the agent, `@AutoValhalla` is a harmless marker and the classes stay
ordinary identity classes.

## Prerequisites

In the default `safe` mode the agent will only convert a class that:

* is `final` (abstract classes are not yet supported);
* extends only `java.lang.Object` or `java.lang.Record`;
* has at least one instance field;
* has only `final` instance fields;
* has no `synchronized` instance methods and no `synchronized` blocks.

If any condition is not met, transformation fails (by default with a
`LinkageError`). Other modes such as `mark-class-final` or
`remove-synchronized` relax individual conditions — see the
[auto-valhalla-agent documentation](../auto-valhalla-agent#mode).

## Verify at build time

`AutoValhallaVerifier` checks the structural prerequisites at build time, before
you ever run the agent:

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